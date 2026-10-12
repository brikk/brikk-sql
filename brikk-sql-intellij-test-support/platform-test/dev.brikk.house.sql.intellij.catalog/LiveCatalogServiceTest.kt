package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.datagrid.DataProducer
import com.intellij.database.datagrid.GridDataRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * The live catalog service over an embedded DuckDB, reached through the platform's remote JDBC
 * wrapper as connections are in the IDE, with real consoles ([RecordingConsole]) for sessions.
 */
class LiveCatalogServiceTest : BasePlatformTestCase() {
    /** A console's engine: it runs the session's requests and opens its connections. */
    class Engine : DataProducer, ConnectionRequestor by ConnectionRequestor.Anonymous() {
        override fun processRequest(request: GridDataRequest) = throw UnsupportedOperationException("not executed")
    }

    private lateinit var errors: BackgroundErrors
    private lateinit var dir: Path
    private lateinit var harvester: CountingHarvester
    private lateinit var service: LiveService
    private lateinit var fileA: PsiFile
    private lateinit var fileB: PsiFile
    private lateinit var consoleA: RecordingConsole
    private lateinit var consoleB: RecordingConsole
    private lateinit var dataSource: LocalDataSource
    private val engines = listOf(Engine(), Engine())
    private val jdbc = ArrayList<Connection>()

    /** The service holds connections weakly; an engine holds them in the IDE. */
    private val connections = ArrayList<Any>()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        dir = Path.of(PathManager.getSystemPath(), "live-catalog-service", UUID.randomUUID().toString())
        harvester = CountingHarvester()
        newService(LiveProfile(harvester))
        fileA = myFixture.configureByText("a-${UUID.randomUUID()}.sql", "SELECT 1")
        fileB = myFixture.addFileToProject("b-${UUID.randomUUID()}.sql", "SELECT 2")
        consoleA = RecordingConsole(project, fileA.virtualFile, "postgresql")
        dataSource = consoleA.console.dataSource
        consoleB = RecordingConsole(project, fileB.virtualFile, dataSource)
        jdbc += embeddedDuckdb()
        jdbc += sameDatabase(jdbc[0])
        jdbc += sameDatabase(jdbc[0])
    }

    override fun tearDown() = RunAll(
        { errors.awaitScopes() },
        { errors.assertNone() },
        { jdbc.forEach(Connection::close) },
        { consoleB.close() },
        { consoleA.close() },
        { dir.toFile().deleteRecursively() },
        { super.tearDown() },
    ).run()

    private fun newService(profile: LiveCatalogProfile): LiveService {
        service = LiveService(errors.newScope(testRootDisposable), profile, dir)
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(LiveService::class.java, service, testRootDisposable)
        return service
    }

    private fun connect(index: Int, requestor: ConnectionRequestor, point: LocalDataSource = dataSource, dbms: Dbms = Dbms.POSTGRES) =
        connected(databaseConnection(jdbc[index], point, dbms, requestor).also { connections += it })

    private fun execute(index: Int, sql: String) = jdbc[index].createStatement().use { it.execute(sql) }

    /** As the observer calls it: on the session's engine thread, never the EDT. */
    private fun changed(console: RecordingConsole, engine: Engine) =
        ApplicationManager.getApplication().executeOnPooledThread { service.inventoryChanged(project, console.console.session, engine, sessionFacts = true) }.get(30, TimeUnit.SECONDS)

    /** Links a console's session to its engine as its first observed request does. */
    private fun link(console: RecordingConsole, engine: Engine) =
        ExecutionObserverTest.Observer(project, errors.newScope(testRootDisposable), ExecutionObserverTest.Profile(liveCatalog = LiveService::class.java, autoIntrospect = null, rules = StatementRules.DUCKDB))
            .requestFinished(finished(console.console, engine, "SELECT 1"))

    private fun offered(file: PsiFile) = service.completion(file).functions.map { it.name }

    fun testTheFirstConnectionHarvestsOncePerSession() {
        connect(2, otherRequestor())
        connect(0, engines[0])
        assertEquals("later connections do not harvest", 1, harvester.harvests.get())
        val completion = service.completion(fileA)
        assertInstanceOf(completion.selection.source, CatalogSource.Live::class.java)
        assertTrue("the engine's own functions", "duckdb_functions" in offered(fileA))
        assertEquals("its keywords", true, completion.keywords?.isReserved("SELECT"))
    }

    fun testEveryConsoleConnectionHarvestsAndOtherConnectionsOnlyTheFirstTime() {
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT))
        connect(2, otherRequestor())
        connect(2, otherRequestor())
        assertEquals(1, harvester.harvests.get())
        connect(0, engines[0])
        connect(1, engines[1])
        assertEquals(3, harvester.harvests.get())
    }

    fun testOnRefreshConnectionsDoNotHarvest() {
        newService(LiveProfile(harvester, HarvestTrigger.ON_REFRESH))
        connect(0, engines[0])
        assertEquals(0, harvester.harvests.get())
        assertEquals(CatalogSource.Bundled, service.completion(fileA).selection.source)
    }

    fun testARegistryAliasOfTheDbmsIsAccepted() {
        registerDbms(CatalogTestDbms.TRINO_BRIKK, testRootDisposable)
        newService(LiveProfile(harvester, dbms = CatalogTestDbms.TRINO_BRIKK))
        connect(0, engines[0], dbms = checkNotNull(Dbms.byName("TRINO")))
        assertEquals(1, harvester.harvests.get())
        connect(0, engines[0], dbms = Dbms.MYSQL)
        assertEquals(1, harvester.harvests.get())
    }

    fun testTwoSessionsKeepTheirOwnTemporaryMacros() {
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT))
        connect(0, engines[0])
        connect(1, engines[1])
        execute(0, "CREATE TEMP MACRO only_in_a(x) AS x + 1")
        changed(consoleA, engines[0])
        execute(1, "CREATE TEMP MACRO only_in_b(x) AS x + 2")
        changed(consoleB, engines[1])
        assertTrue("only_in_a" in offered(fileA))
        assertFalse("only_in_b" in offered(fileA))
        assertTrue("only_in_b" in offered(fileB))
        assertFalse("only_in_a" in offered(fileB))
        assertFalse("a session's facts are not the server's", service.completion(fileA).selection.inventory?.functions.orEmpty().any { it.def.name == "only_in_a" })
        connect(1, engines[1])
        assertTrue("another connection's harvest keeps a session's facts", "only_in_a" in offered(fileA))
    }

    fun testARestartRestoresTheSharedInventoryButNoSessionFacts() {
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT))
        connect(0, engines[0])
        execute(0, "CREATE TEMP MACRO only_in_a(x) AS x + 1")
        changed(consoleA, engines[0])
        assertTrue("only_in_a" in offered(fileA))
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT))
        link(consoleA, engines[0])
        val completion = service.completion(fileA)
        assertInstanceOf(completion.selection.source, CatalogSource.Disk::class.java)
        assertTrue("duckdb_functions" in completion.functions.map { it.name })
        assertFalse("only_in_a" in completion.functions.map { it.name })
    }

    fun testAFailedOrIncompleteRefreshKeepsTheValidSnapshot() {
        connect(0, engines[0])
        val before = service.completion(fileA).selection
        harvester.answer = { throw IllegalStateException("server gone") }
        changed(consoleA, engines[0])
        harvester.answer = { null }
        changed(consoleA, engines[0])
        assertEquals(3, harvester.harvests.get())
        assertSame(before.inventory, service.completion(fileA).selection.inventory)
        assertInstanceOf(service.completion(fileA).selection.source, CatalogSource.Live::class.java)
    }

    fun testAChangedIdentityIgnoresWhatTheOldOneHarvested() {
        connect(0, engines[0])
        dataSource.url = "jdbc:duckdb:/elsewhere.db"
        assertEquals(CatalogSource.Bundled, service.completion(fileA).selection.source)
        assertEquals(CatalogSource.Bundled, newService(LiveProfile(harvester)).completion(fileA).selection.source)
        connect(0, engines[0])
        assertEquals("the old identity's file is gone", 1, dir.listDirectoryEntries().size)
    }

    fun testAHarvestFromTheServerTheDataSourceNamedBeforeIsDiscarded() {
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT))
        harvester.answer = { rows -> DuckdbTestHarvester.harvest(rows).also { dataSource.url = "jdbc:duckdb:/moved.db" } }
        connect(0, engines[0])
        assertEquals("a harvest in flight while the URL changed", CatalogSource.Bundled, service.completion(fileA).selection.source)
        harvester.answer = DuckdbTestHarvester::harvest
        execute(0, "CREATE TEMP MACRO old_server(x) AS x")
        changed(consoleA, engines[0])
        assertEquals("the console's connection still reaches the old server", 1, harvester.harvests.get())
        assertEquals(CatalogSource.Bundled, service.completion(fileA).selection.source)
    }

    fun testANewerLiveServerBeatsTheOlderBundledSnapshot() {
        harvester.answer = { Harvest(inventory(liveFunction("abs", "DOUBLE", returnType = "DOUBLE"), liveFunction("from_the_future"), version = "9.9.0")) }
        connect(0, engines[0])
        val completion = service.completion(fileA)
        assertEquals("the newest bundled snapshot serves a newer server", service.profile.metadata.snapshots.last(), completion.snapshot)
        assertEquals("DOUBLE", completion.functions.single { it.name == "abs" }.signature)
        assertTrue("from_the_future" in completion.functions.map { it.name })
    }

    fun testAHarvestPastItsDeadlineStopsItsQueryBeforeTheConnectionIsHandedOver() {
        connect(0, engines[0])
        val before = service.completion(fileA).selection.inventory
        val queryEnded = AtomicBoolean()
        harvester.answer = { rows ->
            try {
                rows.forEachRow("SELECT sum(i % 7) FROM range(1000000000000) t(i)") { }
            } finally {
                queryEnded.set(true)
            }
            null
        }
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT, deadline = 300.milliseconds))
        val started = TimeSource.Monotonic.markNow()
        connected(databaseConnection(ignoringInterrupts(jdbc[0]), dataSource, Dbms.POSTGRES, engines[0]).also { connections += it })
        val waited = started.elapsedNow()
        assertTrue("the harvest ran into its deadline: $waited", waited >= 300.milliseconds)
        assertTrue("the connection waited $waited", waited < 2.seconds)
        assertTrue("the query ended before the connection was handed over", queryEnded.get())
        assertEquals(42, jdbc[0].createStatement().use { it.executeQuery("SELECT 42").use { rows -> rows.next(); rows.getInt(1) } })
        assertEquals(before?.functions, service.completion(fileA).selection.inventory?.functions)
    }

    fun testCancellingTheConnectCancelsTheHarvestsQuery() {
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT, deadline = 60.seconds))
        val running = CountDownLatch(1)
        val queryEnded = AtomicBoolean()
        harvester.answer = { rows ->
            running.countDown()
            try {
                rows.forEachRow("SELECT sum(i % 7) FROM range(1000000000000) t(i)") { }
            } finally {
                queryEnded.set(true)
            }
            null
        }
        val connection = databaseConnection(ignoringInterrupts(jdbc[0]), dataSource, Dbms.POSTGRES, engines[0]).also { connections += it }
        runBlocking {
            val connecting = async(Dispatchers.Default) { connected(connection) }
            assertTrue(running.await(10, TimeUnit.SECONDS))
            connecting.cancel()
            assertTrue(runCatching { connecting.await() }.exceptionOrNull() is CancellationException)
        }
        assertTrue("the query was cancelled", queryEnded.get())
    }

    fun testASessionInATransactionIsNotHarvested() {
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT))
        connect(0, engines[0])
        jdbc[0].autoCommit = false
        execute(0, "CREATE TEMP MACRO in_tx(x) AS x")
        changed(consoleA, engines[0])
        assertEquals(1, harvester.harvests.get())
        jdbc[0].commit()
        jdbc[0].autoCommit = true
        changed(consoleA, engines[0])
        assertTrue("in_tx" in offered(fileA))
    }

    fun testADisabledProfileHarvestsNothing() {
        newService(LiveProfile(harvester, enabled = false))
        connect(0, engines[0])
        runBlocking { assertEquals(RefreshOutcome.Disabled, service.refresh(project, dataSource)) }
        assertEquals(0, harvester.harvests.get())
        assertEquals(LiveCatalogSelection.BUNDLED, service.completion(fileA).selection)
    }

    fun testAnOldDataSourceFileIsMigratedOnFirstUse() {
        val legacy = LiveCatalogStoreTest.TestLegacyFiles(dir.resolve("old"))
        Files.createDirectories(legacy.root)
        Files.writeString(legacy.file(dataSource.uniqueId), "abs\nmy_old_function\n")
        newService(LiveProfile(harvester, legacyFiles = legacy))
        val completion = service.completion(fileA)
        assertInstanceOf(completion.selection.source, CatalogSource.Disk::class.java)
        assertTrue("my_old_function" in completion.functions.map { it.name })
        assertFalse(Files.exists(legacy.file(dataSource.uniqueId)))
    }

    fun testADeletedDataSourceLosesItsFiles() {
        class Cleanup : LiveCatalogCleanup(LiveService::class.java)
        connect(0, engines[0])
        val other = LocalDataSource().apply { name = "other ${UUID.randomUUID()}"; url = "jdbc:duckdb:" }
        LocalDataSourceManager.getInstance(project).addDataSource(other)
        connect(2, otherRequestor(), point = other)
        assertEquals(2, dir.listDirectoryEntries().size)
        project.messageBus.connect(testRootDisposable).subscribe(DataSourceStorage.TOPIC, Cleanup())
        LocalDataSourceManager.getInstance(project).removeDataSource(other)
        assertEquals(1, dir.listDirectoryEntries().size)
        assertEquals(CatalogSource.Bundled, service.selection(other).source)
    }
}
