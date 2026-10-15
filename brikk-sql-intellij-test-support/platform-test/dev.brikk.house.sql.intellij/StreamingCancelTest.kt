package dev.brikk.house.sql.intellij

import com.intellij.database.DataBus
import com.intellij.database.Dbms
import com.intellij.database.connection.throwable.info.ErrorInfo
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.psi.DbPsiFacade
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.catalog.FinishedQuery
import dev.brikk.house.sql.intellij.slowstream.SlowStreamDriver
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

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
    private val fetching = CopyOnWriteArrayList<DataRequest>()
    private val finished = CopyOnWriteArrayList<DataRequest>()
    private val executionErrors = CopyOnWriteArrayList<Pair<DataRequest, String>>()

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
        { PlatformTestUtil.waitWithEventsDispatching("the sessions end their requests", { sessions.values.all { it.isIdle } }, 30) },
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
        val successor = stream(console, "next")
        workers.single().run()
        PlatformTestUtil.waitWithEventsDispatching("the successor completes", { successor in finished }, 30)
        assertEmpty(errorsOf(successor))
        assertEquals(listOf("execute", "end"), steps("first").distinct())
        assertEquals("the successor is neither cancelled nor cleaned up", listOf("execute", "end"), steps("next").distinct())
        assertFalse(console.databaseSession.isCancelled)
    }

    fun testARequestReplacedWhileTheDriverCancelsGetsNoStockCleanup() {
        val console = open()
        console.run("SELECT 1")
        install(dataSource.dbms)
        val held = stream(console, SlowStreamDriver.HELD)
        pressCancel(console)
        PlatformTestUtil.waitWithEventsDispatching("the driver cancel ends the request and is still running", { held in finished }, 10)
        assertEquals(listOf("execute", "cancel", "close"), steps(SlowStreamDriver.HELD))
        // The connection cancel still running may reach the successor's statement too (the API cancels every
        // statement of the connection), which this one survives: what is checked is the stock cleanup.
        val successor = stream(console, SlowStreamDriver.STEADY)
        Files.createFile(Path.of("$log.release"))
        PlatformTestUtil.waitWithEventsDispatching("the successor completes", { successor in finished }, 30)
        assertEmpty(errorsOf(successor))
        assertEquals("the stock cleanup leaves the successor alone", listOf("execute", "end"), steps(SlowStreamDriver.STEADY).filter { it != "cancel" }.distinct())
        assertFalse(console.databaseSession.isCancelled)
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

    private companion object {
        /** A plugin's own DuckDB dbms, which the registry lists with the stock `DUCK` the fixture's data source detects. */
        val DUCKDB_BRIKK: Dbms = Dbms.create("DUCKDB_BRIKK", "DUCKDB_BRIKK", { AllIcons.Nodes.DataTables }, Dbms.defaultPattern("duckdbbrikk"))
    }
}
