package dev.brikk.house.sql.intellij

import com.intellij.database.DataBus
import com.intellij.database.Dbms
import com.intellij.database.connection.throwable.info.ErrorInfo
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcEngine
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataProducer
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.util.GuardedRef
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.catalog.FinishedQuery
import dev.brikk.house.sql.intellij.slowstream.SlowStreamDriver
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import java.io.EOFException
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.rmi.RemoteException
import java.rmi.UnmarshalException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.time.Duration.Companion.seconds

/**
 * A real console streams a query's results from the IDE's remote JDBC process through [SlowStreamDriver],
 * whose statements behave as ClickHouse JDBC 0.10's do: a closed statement ignores `cancel`, and closing a
 * stream that was not cancelled reads it to the end. Cancel goes through the registered action chain.
 */
class StreamingCancelTest : BasePlatformTestCase() {
    private class Cancel(dbms: Dbms) : StreamingCancelConfiguration(DialectDataSourceResolver(dbms))

    private lateinit var errors: BackgroundErrors
    private lateinit var work: Path
    private lateinit var log: Path
    private lateinit var dataSource: LocalDataSource
    private var installed: Cancel? = null
    private val sessions = LinkedHashMap<JdbcConsole, DatabaseSession>()
    /** Sessions a test left busy by failing one of the platform's own steps on purpose. */
    private val faulted = HashSet<DatabaseSession>()
    private val fetching = CopyOnWriteArrayList<DataRequest>()
    private val finished = CopyOnWriteArrayList<DataRequest>()
    private val executionErrors = CopyOnWriteArrayList<Pair<DataRequest, String>>()
    private val releases = ArrayList<CountDownLatch>()
    private val cancelWorkers = ArrayList<CancelWorker>()
    private val disposals = ArrayList<CountDownLatch>()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        work = Files.createDirectories(Path.of(PathManager.getSystemPath(), "streaming-cancel", UUID.randomUUID().toString()))
        log = work.resolve("steps.log")
        project.basePath?.let { Files.createDirectories(Path.of(it)) }
        val jars = listOf("duckdb_jdbc", "kotlin-stdlib").map { name ->
            System.getProperty("java.class.path").split(File.pathSeparator).first { name in Path.of(it).fileName.toString() }
        } + slowStreamJar().toString()
        val driver = DatabaseDriverManager.getInstance().createDriver("slow-stream-${UUID.randomUUID()}", "Slow stream",
            UrlTemplate("default", "${SlowStreamDriver.PREFIX}{file}")) as DatabaseDriverImpl
        driver.setDriverClass(SlowStreamDriver::class.java.name)
        driver.vmOptions = "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")} -D${SlowStreamDriver.LOG_PROPERTY}=$log"
        driver.setAdditionalClasspathElements(jars.flatMap { SimpleClasspathElementFactory.createElements("jar://$it!/") })
        dataSource = LocalDataSource().apply {
            name = "slow stream ${UUID.randomUUID()}"
            databaseDriver = driver
            // In memory: each connection its own database, so independent consoles do not share a file.
            url = SlowStreamDriver.PREFIX
            isAutoSynchronize = false
        }
        LocalDataSourceManager.getInstance(project).addDataSource(dataSource)
        DbPsiFacade.getInstance(project).clearCaches()
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun fetchStarted(context: DataRequest.Context, resultSetIndex: Int) {
                fetching += context.request
            }

            override fun error(context: DataRequest.Context, error: ErrorInfo) {
                executionErrors += context.request to error.toString()
            }

            override fun requestFinished(context: DataRequest.Context) {
                finished += context.request
            }
        }, testRootDisposable)
    }

    override fun tearDown() = RunAll(
        { installed?.unregisterActions(ActionManager.getInstance()) },
        { releases.forEach { it.countDown() } },
        { cancelWorkers.forEach { it.awaitEnd() } },
        { disposals.forEach { assertTrue("engine disposal ends", it.await(15, TimeUnit.SECONDS)) } },
        { PlatformTestUtil.waitWithEventsDispatching("the sessions end their requests", { sessions.values.all { it.isIdle || it in faulted } }, 30) },
        { sessions.forEach { (console, session) -> DatabaseSessionViewService.getInstance().destroyView(console); Disposer.dispose(console); Disposer.dispose(session) } },
        { LocalDataSourceManager.getInstance(project).removeDataSource(dataSource) },
        { errors.assertNone() },
        { work.toFile().deleteRecursively() },
        { super.tearDown() },
    ).run()

    /** The driver's classes, for the remote JDBC process. */
    private fun slowStreamJar(): Path {
        val classes = Path.of(checkNotNull(SlowStreamDriver::class.java.getResource("SlowStreamDriver.class")).toURI()).parent
        val pkg = SlowStreamDriver::class.java.packageName.replace('.', '/')
        val jar = work.resolve("slow-stream.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.list(classes).use { files ->
                files.filter { it.toString().endsWith(".class") }.sorted().forEach { file ->
                    out.putNextEntry(JarEntry("$pkg/${file.fileName}"))
                    Files.copy(file, out)
                    out.closeEntry()
                }
            }
        }
        return jar
    }

    private fun open(): JdbcConsole {
        val file = myFixture.addFileToProject("slow-${UUID.randomUUID()}.sql", "SELECT 1")
        val session = DatabaseSessionManager.openSession(project, dataSource, "slow ${UUID.randomUUID()}")
        return JdbcConsole.newConsole(project).forFile(file.virtualFile).fromDataSource(dataSource).useSession(session).build()
            .also { sessions[it] = session }
    }

    private val JdbcConsole.databaseSession: DatabaseSession get() = checkNotNull(sessions[this])

    private fun JdbcConsole.submit(sql: String): DataRequest = FinishedQuery(this, sql).also { databaseSession.messageBus.dataProducer.processRequest(it) }

    private fun errorsOf(request: DataRequest) = executionErrors.filter { it.first === request }.map { it.second }

    private fun JdbcConsole.run(sql: String) {
        val request = submit(sql)
        PlatformTestUtil.waitWithEventsDispatching("'$sql' finishes", { request in finished }, 30)
        assertEmpty(errorsOf(request))
    }

    private fun install(dbms: Dbms) {
        installed = Cancel(dbms).also { it.registerActions(ActionManager.getInstance()) }
    }

    private fun cancelEvent(console: JdbcConsole) =
        TestActionEvent.createTestEvent(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(SessionClientHolder.CLIENT_KEY, console).build())

    private fun pressCancel(console: JdbcConsole) {
        checkNotNull(ActionManager.getInstance().getAction(CooperativeActionConfiguration.CANCEL)).actionPerformed(cancelEvent(console))
    }

    /** The driver's steps for the slow query tagged [tag]. */
    private fun steps(tag: String): List<String> =
        if (Files.exists(log)) Files.readAllLines(log).filter { it.startsWith("$tag ") }.map { it.removePrefix("$tag ") } else emptyList()

    /** Starts the slow query tagged [tag] in [console] and waits until its results stream. */
    private fun stream(console: JdbcConsole, tag: String): DataRequest {
        val request = console.submit("SELECT '${SlowStreamDriver.MARKER} $tag'")
        PlatformTestUtil.waitWithEventsDispatching("the results stream", { request in fetching }, 30)
        return request
    }

    fun testStreamingCancelCancelsTheStatementBeforeTheCleanupLeavesAnotherConsoleAndRunsAgain() {
        val other = open()
        other.run("SELECT 1")
        val console = open()
        console.run("SELECT 1")
        install(dataSource.dbms)
        val independent = stream(other, "other")
        val request = stream(console, "owned")
        pressCancel(console)
        // The EDT stays blocked: the statement is cancelled without it.
        val blocked = System.nanoTime()
        while ("cancel" !in steps("owned") && System.nanoTime() - blocked < 5_000_000_000) Thread.sleep(20)
        assertTrue("cancelled off the EDT: ${steps("owned")}", "cancel" in steps("owned"))
        PlatformTestUtil.waitWithEventsDispatching("the cancelled request ends", { request in finished }, 5)
        assertEquals("cancelled while open, then closed without reading the rest", listOf("execute", "cancel", "close"), steps("owned"))
        assertFalse("the other console still streams", independent in finished)
        PlatformTestUtil.waitWithEventsDispatching("the session is idle", { console.databaseSession.isIdle }, 10)
        console.run("SELECT 42")
        PlatformTestUtil.waitWithEventsDispatching("the other console's query completes", { independent in finished }, 30)
        assertEmpty(errorsOf(independent))
        assertEquals("the other console's statement read its results to the end", listOf("execute", "end"), steps("other").distinct())
    }

    fun testACaptureThatIsStaleWhenItsWorkerBeginsCancelsNothing() {
        val console = open()
        console.run("SELECT 1")
        val workers = ArrayList<Runnable>()
        val interceptor = StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms), workers::add)
        val first = stream(console, "first")
        assertEquals(ExecutionClaim.HANDLED, interceptor.perform(cancelEvent(console)) { fail("a streaming request is claimed") })
        PlatformTestUtil.waitWithEventsDispatching("the captured request completes", { first in finished }, 30)
        // The engine starts the successor only once the cancel has run.
        val successor = console.submit("SELECT '${SlowStreamDriver.MARKER} next'")
        workers.single().run()
        PlatformTestUtil.waitWithEventsDispatching("the successor completes", { successor in finished }, 30)
        assertEmpty(errorsOf(successor))
        assertEquals(listOf("execute", "end"), steps("first").distinct())
        assertEquals("the successor is neither cancelled nor cleaned up", listOf("execute", "end"), steps("next").distinct())
        assertFalse(console.databaseSession.isCancelled)
    }

    fun testARequestSubmittedWhileTheDriverCancelsIsLeftAlone() {
        val console = open()
        console.run("SELECT 1")
        install(dataSource.dbms)
        val held = stream(console, SlowStreamDriver.HELD)
        pressCancel(console)
        val pressed = System.nanoTime()
        while ("cancel" !in steps(SlowStreamDriver.HELD) && System.nanoTime() - pressed < 5_000_000_000) Thread.sleep(20)
        // The driver cancel has stopped the request and is still running when the next request is submitted.
        val successor = console.submit("SELECT '${SlowStreamDriver.MARKER} next'")
        PlatformTestUtil.waitWithEventsDispatching("the cancelled request ends", { held in finished }, 10)
        val ended = System.nanoTime()
        while (steps("next").isEmpty() && System.nanoTime() - ended < 2_000_000_000) Thread.sleep(20)
        assertEmpty("the successor waits for the connection cancel", steps("next"))
        Files.createFile(Path.of("$log.release"))
        PlatformTestUtil.waitWithEventsDispatching("the successor completes", { successor in finished }, 30)
        assertEquals(listOf("execute", "cancel", "close", "cancel returns"), steps(SlowStreamDriver.HELD))
        val order = Files.readAllLines(log)
        assertTrue("the successor opens its statement after the connection cancel: $order",
            order.indexOf("${SlowStreamDriver.HELD} cancel returns") < order.indexOf("next execute"))
        assertEmpty(errorsOf(successor))
        assertEquals("the successor is neither cancelled nor cleaned up", listOf("execute", "end"), steps("next").distinct())
        assertFalse(console.databaseSession.isCancelled)
    }

    fun testAConnectionCancelPastTheDeadlineDisconnectsBeforeTheNextRequestRuns() {
        val console = open()
        console.run("SELECT 1")
        val interceptor = StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms), deadline = 1.seconds)
        stream(console, SlowStreamDriver.HELD)
        assertEquals(ExecutionClaim.HANDLED, interceptor.perform(cancelEvent(console)) { fail("a streaming request is claimed") })
        // Queued while the connection cancel runs: the disconnect drops it with the connection.
        console.submit("SELECT '${SlowStreamDriver.MARKER} queued'")
        PlatformTestUtil.waitWithEventsDispatching("the console disconnects past the deadline", { !console.databaseSession.isConnected }, 10)
        assertFalse("the connection cancel is still running", "cancel returns" in steps(SlowStreamDriver.HELD))
        val successor = console.submit("SELECT '${SlowStreamDriver.MARKER} next'")
        PlatformTestUtil.waitWithEventsDispatching("the successor completes", { successor in finished }, 30)
        assertEmpty("nothing ran on the disconnected connection", steps("queued"))
        assertEmpty(errorsOf(successor))
        assertEquals("the successor is neither cancelled nor cleaned up", listOf("execute", "end"), steps("next").distinct())
        assertFalse("the successor runs on a new connection, out of the running cancel's reach",
            steps("@next") == steps("@${SlowStreamDriver.HELD}"))
        Files.createFile(Path.of("$log.release"))
        // The retired connection's cancel is deliberately still running. Do not destroy its driver
        // process in teardown before it observes release: that leaks an RMI EOF into the next test.
        PlatformTestUtil.waitWithEventsDispatching("the retired connection's cancel returns", {
            "cancel returns" in steps(SlowStreamDriver.HELD)
        }, 10)
        console.run("SELECT 42")
    }

    fun testAFailedConnectionCancelIsReportedAndStillAllowsTheStockCleanup() = failedCancel(afterDisconnect = false)

    fun testAConnectionCancelThatFailsAfterRetirementIsStillReportedWithoutABackgroundError() = failedCancel(afterDisconnect = true)

    /** A cancel reply fails either on the current connection or after its worker has retired it and returned. */
    private fun failedCancel(afterDisconnect: Boolean) {
        val console = open()
        console.run("SELECT 1")
        val release = CountDownLatch(if (afterDisconnect) 1 else 0)
        val returned = CountDownLatch(1)
        val cleanup = AtomicInteger()
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun requestCancelStarted(context: DataRequest.Context, cancelRequest: DataRequest.Cancel?) {
                cleanup.incrementAndGet()
            }
        }, testRootDisposable)
        val worker = Executor { work ->
            ApplicationManager.getApplication().executeOnPooledThread(Runnable { try { work.run() } finally { returned.countDown() } })
        }
        val interceptor = StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms), worker, 1.seconds)
        val tag = if (afterDisconnect) SlowStreamDriver.LAGGING else "failed"
        val request = stream(console, tag)
        val failure = if (afterDisconnect) UnmarshalException("the cancel reply was lost", EOFException("synthetic cancel EOF"))
            else RemoteException("the cancel could not be sent")
        interceptCancel(request) { remote ->
            // Before the deadline, fail without stopping the stream so the stock cleanup is still needed.
            if (afterDisconnect) remote.cancelAll()
            check(release.await(30, TimeUnit.SECONDS)) { "the test did not release the cancel reply" }
            throw failure
        }
        val warnings = CopyOnWriteArrayList<Throwable>()
        val unexpected = CopyOnWriteArrayList<Throwable>()
        val logging = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                if (message == "Cancelling a streaming request failed" && t != null) warnings += t
                return super.processWarn(category, message, t)
            }

            // Keep checking every error while this warning recorder temporarily replaces BackgroundErrors' processor.
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                unexpected += t ?: AssertionError(message)
                return super.processError(category, message, details, t)
            }
        })
        try {
            assertEquals(ExecutionClaim.HANDLED, interceptor.perform(cancelEvent(console)) { fail("a streaming request is claimed") })
            if (afterDisconnect) {
                PlatformTestUtil.waitWithEventsDispatching("the timed out console disconnects", { !console.databaseSession.isConnected }, 10)
                assertTrue("the worker returns without waiting on the retired connection", returned.await(10, TimeUnit.SECONDS))
                assertEmpty("the cancel reply is still held", warnings)
            } else {
                PlatformTestUtil.waitWithEventsDispatching("the failed cancel and stock cleanup return", { returned.count == 0L }, 30)
                assertEquals("the current request still receives the stock cleanup", 1, cleanup.get())
                assertTrue("a failed reply before the deadline does not disconnect", console.databaseSession.isConnected)
            }
            val successor = console.submit("SELECT '${SlowStreamDriver.MARKER} next'")
            PlatformTestUtil.waitWithEventsDispatching("the successor completes", { successor in finished }, 30)
            assertEmpty(errorsOf(successor))
            assertEquals("the successor is neither cancelled nor cleaned up", listOf("execute", "end"), steps("next").distinct())
            assertEquals("only a retired connection is replaced", !afterDisconnect, steps("@next") == steps("@$tag"))
            release.countDown()
            PlatformTestUtil.waitWithEventsDispatching("the cancel failure is reported even after retirement", { warnings.isNotEmpty() }, 10)
            assertEquals("report the original failure exactly once", listOf(failure), warnings.toList())
        } finally {
            release.countDown()
            logging.finish()
            assertEmpty("no cancel failure escapes to the platform's pooled-thread error logger", unexpected)
        }
    }

    /** Replace only this live connection's cancel call; the engine and the interceptor retain the same identity. */
    private fun interceptCancel(request: DataRequest, cancel: (RemoteConnection) -> Unit) {
        val connection = DatabaseConnectionManager.getInstance().activeConnections.single {
            (it.requestor as? JdbcEngine)?.requestContextIfAny?.request === request
        }
        val remote = connection.remoteConnection
        val field = connection.javaClass.declaredFields.single { it.type == RemoteConnection::class.java }.apply { isAccessible = true }
        field.set(connection, Proxy.newProxyInstance(RemoteConnection::class.java.classLoader, arrayOf(RemoteConnection::class.java)) { _, method, args ->
            if (method.name == "cancelAll") cancel(remote) else try {
                method.invoke(remote, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        })
        Disposer.register(testRootDisposable) { field.set(connection, remote) }
    }

    fun testADisconnectThatCannotBeSentHoldsTheNextRequestUntilTheConnectionCancelEnds() = disconnectRefused(ownershipFails = false)

    /** As the platform closing the engine's connection meanwhile makes it: asking who owns it fails. */
    fun testAnOwnershipCheckThatFailsAfterTheRefusedDisconnectHoldsTheNextRequestUntilTheConnectionCancelEnds() =
        disconnectRefused(ownershipFails = true)

    /**
     * The connection cancel is held past the deadline and the disconnect cannot be sent; with [ownershipFails],
     * reading the engine's connection on the cancel's worker fails from then on too.
     */
    private fun disconnectRefused(ownershipFails: Boolean) {
        val console = open()
        console.run("SELECT 1")
        val refusal = "the disconnect cannot be sent"
        val refused = CountDownLatch(1)
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun jobSubmitted(request: DataRequest, producer: DataProducer) {
                if (request is DataRequest.Disconnect && refused.count > 0) {
                    refused.countDown()
                    throw IllegalStateException(refusal)
                }
            }
        }, testRootDisposable)
        // The message bus logs a failing listener; in tests that log rethrows, which makes sending the disconnect
        // fail. The session counts the job, which never finishes.
        val logging = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                if (generateSequence(t) { it.cause }.any { it.message == refusal }) setOf(Action.RETHROW) else super.processError(category, message, details, t)
        })
        Disposer.register(testRootDisposable) { logging.finish() }
        faulted += console.databaseSession
        val thrown = CopyOnWriteArrayList<Throwable>()
        var workerThread: Thread? = null
        val worker = Executor { work -> Thread { try { work.run() } catch (e: Throwable) { thrown += e } }.also { workerThread = it }.start() }
        val interceptor = StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms), worker, 1.seconds)
        val held = stream(console, SlowStreamDriver.HELD)
        val ownershipFailures = AtomicInteger()
        if (ownershipFails) {
            val engine = DatabaseConnectionManager.getInstance().activeConnections.mapNotNull { it.requestor as? JdbcEngine }
                .single { it.requestContextIfAny?.request === held }
            // The engine's GuardedRef connection holder, whose private name varies by IDE build.
            val field = JdbcEngine::class.java.declaredFields.single {
                GuardedRef::class.java.isAssignableFrom(it.type)
            }.apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val original = field.get(engine) as GuardedRef<DatabaseConnection>
            field.set(engine, object : GuardedRef<DatabaseConnection>(original.get()) {
                override fun get(): DatabaseConnection {
                    if (Thread.currentThread() === workerThread && refused.count == 0L) {
                        ownershipFailures.incrementAndGet()
                        throw IllegalStateException("the connection is closing")
                    }
                    return super.get()
                }

                override fun close(value: DatabaseConnection) = original.close()
            })
        }
        assertEquals(ExecutionClaim.HANDLED, interceptor.perform(cancelEvent(console)) { fail("a streaming request is claimed") })
        val successor = console.submit("SELECT '${SlowStreamDriver.MARKER} next'")
        assertTrue("the disconnect was refused past the deadline", refused.await(10, TimeUnit.SECONDS))
        PlatformTestUtil.waitWithEventsDispatching("the cancelled request ends", { held in finished }, 10)
        val waiting = System.nanoTime()
        while (System.nanoTime() - waiting < 2_000_000_000) Thread.sleep(20)
        assertEmpty("the successor waits for the connection cancel", steps("next"))
        assertTrue("the connection stays", console.databaseSession.isConnected)
        Files.createFile(Path.of("$log.release"))
        PlatformTestUtil.waitWithEventsDispatching("the successor completes", { successor in finished }, 30)
        assertEmpty(errorsOf(successor))
        assertEquals("the successor is neither cancelled nor cleaned up", listOf("execute", "end"), steps("next").distinct())
        val order = Files.readAllLines(log)
        assertTrue("the successor opens its statement after the connection cancel: $order",
            order.indexOf("${SlowStreamDriver.HELD} cancel returns") < order.indexOf("next execute"))
        assertEquals("on the same connection", steps("@next"), steps("@${SlowStreamDriver.HELD}"))
        assertEquals("the ownership check failed while finishing", ownershipFails, ownershipFailures.get() > 0)
        assertTrue("the refusal propagates once the engine can go on: $thrown",
            thrown.size == 1 && generateSequence(thrown.single()) { it.cause }.any { it.message == refusal })
    }

    fun testAStockCleanupHandledPastTheDeadlineStillHoldsTheNextRequest() {
        val console = open()
        console.run("SELECT 1")
        val cleanup = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun requestCancelStarted(context: DataRequest.Context, cancelRequest: DataRequest.Cancel?) {
                cleanupStarted.countDown()
                cleanup.await(30, TimeUnit.SECONDS)
            }
        }, testRootDisposable)
        val interceptor = StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms), deadline = 1.seconds)
        val lagging = stream(console, SlowStreamDriver.LAGGING)
        assertEquals(ExecutionClaim.HANDLED, interceptor.perform(cancelEvent(console)) { fail("a streaming request is claimed") })
        assertTrue("the stock cleanup reaches the still running request", cleanupStarted.await(10, TimeUnit.SECONDS))
        PlatformTestUtil.waitWithEventsDispatching("the cancelled request ends", { lagging in finished }, 10)
        val successor = console.submit("SELECT '${SlowStreamDriver.MARKER} next'")
        val ended = System.nanoTime()
        while (System.nanoTime() - ended < 2_000_000_000) Thread.sleep(20)
        assertEmpty("the successor waits past the deadline for the stock cleanup", steps("next"))
        cleanup.countDown()
        PlatformTestUtil.waitWithEventsDispatching("the successor completes", { successor in finished }, 30)
        assertEmpty(errorsOf(successor))
        assertEquals("the successor is neither cancelled nor cleaned up", listOf("execute", "end"), steps("next").distinct())
        assertEquals(steps("@next"), steps("@${SlowStreamDriver.LAGGING}"))
    }

    fun testARejectedSubmissionReleasesTheCapture() {
        val console = open()
        console.run("SELECT 1")
        val interceptor = StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms), { throw RejectedExecutionException("full") })
        val request = stream(console, "rejected")
        var delegated = false
        assertEquals(ExecutionClaim.DECLINED, interceptor.perform(cancelEvent(console)) { delegated = true })
        assertFalse("perform leaves the stock cancel to its caller", delegated)
        PlatformTestUtil.waitWithEventsDispatching("the request completes, held by nothing", { request in finished }, 30)
        console.run("SELECT 42")
    }

    fun testTheConstructorBeforeTheDeadlineRemains() {
        assertNotNull(StreamingCancelInterceptor::class.java.getConstructor(DialectDataSourceResolver::class.java, Executor::class.java))
    }

    fun testAnotherDbmsKeepsTheStockCancel() {
        val console = open()
        console.run("SELECT 1")
        install(Dbms.ORACLE)
        val request = stream(console, "stock")
        pressCancel(console)
        PlatformTestUtil.waitWithEventsDispatching("the stock cancel ends the request", { request in finished }, 30)
        assertEquals("the stock cancel closes the stream without cancelling it", listOf("execute", "drain", "close"), steps("stock"))
    }

    fun testAnAliasTheResolverAcceptsIsDeclinedAndTheExactDbmsIsClaimed() {
        val console = open()
        console.run("SELECT 1")
        val alias = DialectDataSourceResolver(DUCKDB_BRIKK)
        assertTrue("the registry lists ${dataSource.dbms.name} beside ${DUCKDB_BRIKK.name}", alias.accepts(dataSource.dbms))
        val workers = ArrayList<Runnable>()
        val request = stream(console, "alias")
        assertEquals(ExecutionClaim.DECLINED, StreamingCancelInterceptor(alias, workers::add).perform(cancelEvent(console)) { fail("perform never delegates itself") })
        assertEmpty(workers)
        assertEquals(ExecutionClaim.HANDLED, StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms), workers::add)
            .perform(cancelEvent(console)) { fail("a streaming request is claimed") })
        workers.single().run()
        PlatformTestUtil.waitWithEventsDispatching("the claimed cancel ends the request", { request in finished }, 5)
        assertEquals(listOf("execute", "cancel", "close"), steps("alias"))
    }

    fun testAPressWithoutASessionRunsTheWrappedAction() {
        val event = TestActionEvent.createTestEvent(SimpleDataContext.getProjectContext(project))
        var delegated = false
        assertEquals(ExecutionClaim.DECLINED, StreamingCancelInterceptor(DialectDataSourceResolver(Dbms.POSTGRES)).perform(event) { delegated = true })
        assertFalse(delegated)
    }

    fun testConnectionRetirementAloneReleasesTheRequestFinishedWaitBeforeTheWorkerStarts() = parkedReport(dispose = false)

    fun testEngineDisposalAloneDoesNotReleaseTheRequestFinishedWait() = parkedReport(dispose = true)

    private fun parkedReport(dispose: Boolean) {
        val console = open()
        console.run("SELECT 1")
        val workers = ArrayList<Runnable>()
        val interceptor = StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms), workers::add)
        val request = stream(console, "beforeWorker")
        val engine = engineOf(request)
        try {
            assertEquals(ExecutionClaim.HANDLED, interceptor.perform(cancelEvent(console)) {})
            val reported = afterReport(request)
            PlatformTestUtil.waitWithEventsDispatching("the engine parks at its finished report", { request in finished }, 30)
            assertEquals("the interceptor still holds the report", 1L, reported.count)
            if (dispose) {
                faulted += console.databaseSession
                val connection = connectionOf(request)
                val disposed = disposeEngine(engine)
                PlatformTestUtil.waitWithEventsDispatching("the engine executor is shut down", { engine.isDisposed }, 10)
                assertFalse("disposal alone cannot release requestFinished", reported.await(500, TimeUnit.MILLISECONDS))
                assertTrue(active(connection))
                workers.removeAt(0).run()
                assertTrue(reported.await(10, TimeUnit.SECONDS))
                assertTrue(disposed.await(10, TimeUnit.SECONDS))
            } else {
                console.databaseSession.messageBus.dataProducer.processRequest(DataRequest.Disconnect(console.databaseSession))
                assertTrue("retirement releases the report without running the worker", reported.await(10, TimeUnit.SECONDS))
                assertNewConnection(console, "beforeWorker")
            }
        } finally {
            workers.forEach { it.run() }
        }
    }

    fun testEngineDisposalAloneDoesNotReleaseTheInitialCancelFutureWait() = recoverWait("initial", dispose = true)

    fun testEngineDisposalAloneDoesNotReleaseTheFinalCancelFutureWait() = recoverWait("finally", dispose = true)

    fun testConnectionRetirementReleasesTheFinalCancelFutureWait() = recoverWait("finally", dispose = false)

    fun testEngineDisposalReleasesTheStockCleanupWait() = recoverWait("stock", dispose = true)

    fun testConnectionRetirementReleasesTheStockCleanupWait() = recoverWait("stock", dispose = false)

    fun testConnectionRetirementReleasesTheDisconnectHandledWait() = recoverWait("disconnect", dispose = false)

    /** The probe's worker states: cancel future, refused disconnect's finally, and unhandled stock/disconnect. */
    private fun recoverWait(kind: String, dispose: Boolean) {
        val console = open()
        console.run("SELECT 1")
        val session = console.databaseSession
        val releaseCancel = releaseLatch()
        val releaseRequest = releaseLatch()
        val blocked = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val disconnects = CopyOnWriteArrayList<DataRequest>()
        val disconnectHandled = CountDownLatch(1)
        val refusal = "the recovery test refuses its first disconnect"
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun jobSubmitted(request: DataRequest, producer: DataProducer) {
                if (request is DataRequest.Disconnect) disconnects += request
                if (kind == "finally" && request is DataRequest.Disconnect && blocked.count > 0) {
                    blocked.countDown()
                    throw IllegalStateException(refusal)
                }
            }

            override fun requestCancelStarted(context: DataRequest.Context, cancelRequest: DataRequest.Cancel?) {
                if ((kind == "stock" || kind == "disconnect") && blocked.count > 0) {
                    blocked.countDown()
                    check(releaseRequest.await(120, TimeUnit.SECONDS))
                }
            }

            override fun jobFinished(request: DataRequest, producer: DataProducer) {
                if (request === disconnects.firstOrNull()) disconnectHandled.countDown()
            }
        }, testRootDisposable)
        val logging = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                if (generateSequence(t) { it.cause }.any { it.message == refusal }) setOf(Action.RETHROW) else super.processError(category, message, details, t)
        })
        val worker = cancelWorker()
        try {
            val interceptor = StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms), worker, if (kind == "initial") 30.seconds else 1.seconds)
            val tag = if (kind == "disconnect") "awaitDisconnect" else SlowStreamDriver.LAGGING
            val request = stream(console, tag)
            val engine = engineOf(request)
            val connection = connectionOf(request)
            if (kind != "stock") interceptCancel(request) { remote ->
                if (kind != "disconnect") remote.cancelAll()
                if (kind == "initial") blocked.countDown()
                try { check(releaseCancel.await(120, TimeUnit.SECONDS)) } finally { cancelReturned.countDown() }
            }
            assertEquals(ExecutionClaim.HANDLED, interceptor.perform(cancelEvent(console)) {})
            val reported = afterReport(request)
            assertTrue(blocked.await(10, TimeUnit.SECONDS))
            PlatformTestUtil.waitWithEventsDispatching("the captured request reaches requestFinished", { request in finished }, 20)
            worker.awaitWait(if (kind == "stock" || kind == "disconnect") "awaitHandled" else "finishes")
            if (kind == "finally" || dispose) faulted += session
            val disposed = if (dispose) disposeEngine(engine) else null
            if (dispose && kind != "stock") {
                PlatformTestUtil.waitWithEventsDispatching("the engine executor is shut down", { engine.isDisposed }, 10)
                assertFalse("disposal is not retirement", worker.done.await(500, TimeUnit.MILLISECONDS))
                assertEquals("the engine report is still held", 1L, reported.count)
                assertTrue(active(connection))
                releaseCancel.countDown()
            } else if (!dispose) {
                assertTrue("the captured connection is still registered", active(connection))
                session.messageBus.dataProducer.processRequest(DataRequest.Disconnect(session))
            }
            worker.awaitEnd()
            assertTrue("the engine thread leaves its finished report", reported.await(10, TimeUnit.SECONDS))
            if (!dispose && kind != "stock") assertEquals("the remote cancel has not returned", 1L, cancelReturned.count)
            if (disposed != null) assertTrue(disposed.await(10, TimeUnit.SECONDS))
            if (kind == "finally") assertTrue(worker.failures.single().let { generateSequence(it) { it.cause }.any { it.message == refusal } })
            else assertEmpty(worker.failures)
            if (!dispose) {
                assertFalse(active(connection))
                // Finish the old cleanup before reconnecting, without using it to release the worker or engine report.
                releaseRequest.countDown()
                if (kind == "disconnect") assertTrue(disconnectHandled.await(10, TimeUnit.SECONDS))
                assertNewConnection(console, tag)
            }
        } finally {
            releaseRequest.countDown()
            releaseCancel.countDown()
            try {
                worker.awaitEnd()
                if (kind != "stock") assertTrue(cancelReturned.await(10, TimeUnit.SECONDS))
            } finally {
                logging.finish()
            }
        }
    }

    fun testClosingAnotherProjectDoesNotReleaseAPendingCancelOrStopLaterCancelsBeingClaimed() {
        val console = open()
        console.run("SELECT 1")
        val entered = CountDownLatch(1)
        val releaseCancel = releaseLatch()
        val returned = CountDownLatch(1)
        val interceptor = StreamingCancelInterceptor(DialectDataSourceResolver(dataSource.dbms))
        val request = stream(console, SlowStreamDriver.LAGGING)
        val engine = engineOf(request)
        val connection = connectionOf(request)
        interceptCancel(request) { remote ->
            remote.cancelAll()
            entered.countDown()
            try { check(releaseCancel.await(120, TimeUnit.SECONDS)) } finally { returned.countDown() }
        }
        try {
            assertEquals(ExecutionClaim.HANDLED, interceptor.perform(cancelEvent(console)) {})
            val reported = afterReport(request)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            PlatformTestUtil.waitWithEventsDispatching("the engine parks at its finished report", { request in finished }, 10)
            val successor = console.submit("SELECT '${SlowStreamDriver.MARKER} next'")
            assertFalse(engine.isDisposed)
            val other = PlatformTestUtil.loadAndOpenProject(Files.createDirectories(work.resolve("other-project")), testRootDisposable)
            try { assertNotSame(project, other) } finally { PlatformTestUtil.forceCloseProjectWithoutSaving(other) }
            assertTrue("the platform sets this flag even for another project's close", engine.isDisposed)
            assertTrue(active(connection))
            assertFalse("the flag must not release the engine", reported.await(500, TimeUnit.MILLISECONDS))
            assertEmpty("the next request cannot run during cancelAll", steps("next"))
            releaseCancel.countDown()
            assertTrue(returned.await(10, TimeUnit.SECONDS))
            PlatformTestUtil.waitWithEventsDispatching("the successor runs only after cancelAll returns", { successor in finished }, 30)
            assertEmpty(errorsOf(successor))
            assertEquals(steps("@next"), steps("@${SlowStreamDriver.LAGGING}"))
            val later = stream(console, "laterCancel")
            assertEquals("a later press is still claimed despite that flag", ExecutionClaim.HANDLED, interceptor.perform(cancelEvent(console)) {})
            PlatformTestUtil.waitWithEventsDispatching("the later cancel completes", { later in finished }, 10)
            assertEquals(listOf("execute", "cancel", "close"), steps("laterCancel"))
        } finally {
            releaseCancel.countDown()
            assertTrue(returned.await(10, TimeUnit.SECONDS))
        }
    }

    private fun connectionOf(request: DataRequest): DatabaseConnection = DatabaseConnectionManager.getInstance().activeConnections
        .single { (it.requestor as? JdbcEngine)?.requestContextIfAny?.request === request }

    private fun active(connection: DatabaseConnection): Boolean = DatabaseConnectionManager.getInstance().activeConnections.any { it === connection }

    /** Installed after the interceptor, so this fires only after its requestFinished wait actually returns. */
    private fun afterReport(request: DataRequest) = CountDownLatch(1).also { reported ->
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun requestFinished(context: DataRequest.Context) {
                if (context.request === request) reported.countDown()
            }
        }, testRootDisposable)
    }

    private fun assertNewConnection(console: JdbcConsole, oldTag: String) {
        val successor = console.submit("SELECT '${SlowStreamDriver.MARKER} next'")
        PlatformTestUtil.waitWithEventsDispatching("the successor runs on a new connection", { successor in finished }, 30)
        assertEmpty(errorsOf(successor))
        assertFalse("the old cancel cannot reach the new connection", steps("@next") == steps("@$oldTag"))
        assertEquals("the successor is neither cancelled nor cleaned up", listOf("execute", "end"), steps("next").distinct())
    }

    private fun engineOf(request: DataRequest): JdbcEngine = DatabaseConnectionManager.getInstance().activeConnections
        .mapNotNull { it.requestor as? JdbcEngine }.single { it.requestContextIfAny?.request === request }

    private fun releaseLatch() = CountDownLatch(1).also { releases += it }

    private fun disposeEngine(engine: JdbcEngine): CountDownLatch = CountDownLatch(1).also { done ->
        disposals += done
        Thread { try { Disposer.dispose(engine) } finally { done.countDown() } }.start()
    }

    private fun cancelWorker() = CancelWorker().also { cancelWorkers += it }

    private class CancelWorker : Executor {
        val done = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        private lateinit var thread: Thread

        override fun execute(command: Runnable) {
            thread = Thread {
                try { command.run() } catch (e: Throwable) { failures += e } finally { done.countDown() }
            }.also { it.start() }
        }

        fun awaitWait(method: String) {
            PlatformTestUtil.waitWithEventsDispatching("the worker enters $method", {
                thread.stackTrace.any { it.className == "dev.brikk.house.sql.intellij.StreamingRequest" && it.methodName == method }
            }, 10)
        }

        fun awaitEnd() {
            PlatformTestUtil.waitWithEventsDispatching("the cancel worker ends", { done.count == 0L }, 15)
        }
    }

    private companion object {
        /** A plugin's own DuckDB dbms, which the registry lists with the stock `DUCK` the fixture's data source detects. */
        val DUCKDB_BRIKK: Dbms = Dbms.create("DUCKDB_BRIKK", "DUCKDB_BRIKK", { AllIcons.Nodes.DataTables }, Dbms.defaultPattern("duckdbbrikk"))
    }
}
