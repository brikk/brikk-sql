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
        service = newService(LiveProfile(harvester))
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

    private fun newService(profile: LiveCatalogProfile) = LiveService(errors.newScope(testRootDisposable), profile, dir).also { service = it }

    private fun connect(index: Int, requestor: ConnectionRequestor) = runBlocking {
        service.connected(databaseConnection(jdbc[index], dataSource, Dbms.POSTGRES, requestor).also { connections += it })
    }

    private fun execute(index: Int, sql: String) = jdbc[index].createStatement().use { it.execute(sql) }

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

    fun testTwoSessionsKeepTheirOwnTemporaryMacros() {
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT))
        connect(0, engines[0])
        connect(1, engines[1])
        execute(0, "CREATE TEMP MACRO only_in_a(x) AS x + 1")
        service.inventoryChanged(project, consoleA.console.session, engines[0])
        execute(1, "CREATE TEMP MACRO only_in_b(x) AS x + 2")
        service.inventoryChanged(project, consoleB.console.session, engines[1])
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
        service.inventoryChanged(project, consoleA.console.session, engines[0])
        assertTrue("only_in_a" in offered(fileA))
        val restarted = newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT))
        restarted.learnSession(consoleA.console.session, engines[0])
        val completion = restarted.completion(fileA)
        assertInstanceOf(completion.selection.source, CatalogSource.Disk::class.java)
        assertTrue("duckdb_functions" in completion.functions.map { it.name })
        assertFalse("only_in_a" in completion.functions.map { it.name })
    }

    fun testAFailedOrIncompleteRefreshKeepsTheValidSnapshot() {
        connect(0, engines[0])
        val before = service.completion(fileA).selection
        harvester.answer = { throw IllegalStateException("server gone") }
        service.inventoryChanged(project, consoleA.console.session, engines[0])
        harvester.answer = { null }
        service.inventoryChanged(project, consoleA.console.session, engines[0])
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

    fun testANewerLiveServerBeatsTheOlderBundledSnapshot() {
        harvester.answer = { Harvest(inventory(liveFunction("abs", "DOUBLE", returnType = "DOUBLE"), liveFunction("from_the_future"), version = "9.9.0")) }
        connect(0, engines[0])
        val completion = service.completion(fileA)
        assertEquals("the newest bundled snapshot serves a newer server", service.profile.metadata.snapshots.last(), completion.snapshot)
        assertEquals("DOUBLE", completion.functions.single { it.name == "abs" }.signature)
        assertTrue("from_the_future" in completion.functions.map { it.name })
    }

    fun testAHarvestPastItsDeadlineIsAbandoned() {
        connect(0, engines[0])
        val before = service.completion(fileA).selection.inventory
        val release = CountDownLatch(1)
        harvester.answer = { release.await(30, TimeUnit.SECONDS); null }
        newService(LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT, deadline = 300.milliseconds))
        val started = TimeSource.Monotonic.markNow()
        connect(0, engines[0])
        assertTrue("the connection waited ${started.elapsedNow()}", started.elapsedNow() < 5.seconds)
        release.countDown()
        assertEquals(before?.functions, service.completion(fileA).selection.inventory?.functions)
    }

    fun testCancellingTheCallerCancelsTheHarvest() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val blocking = LiveCatalogHarvester {
            started.countDown()
            try {
                Thread.sleep(30_000)
            } catch (e: InterruptedException) {
                interrupted.countDown()
            }
            null
        }
        runBlocking {
            val harvest = async(Dispatchers.Default) { runHarvest(blocking, 30.seconds) { CatalogRowSource { _, _ -> } } }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            harvest.cancel()
            assertThrows(CancellationException::class.java) { runBlocking { harvest.await() } }
        }
        assertTrue("the harvest thread is interrupted", interrupted.await(10, TimeUnit.SECONDS))
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
        service.store.store(other.uniqueId, service.profile.identity(other), inventory(liveFunction("abs")))
        assertEquals(2, dir.listDirectoryEntries().size)
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(LiveService::class.java, service, testRootDisposable)
        project.messageBus.connect(testRootDisposable).subscribe(DataSourceStorage.TOPIC, Cleanup())
        LocalDataSourceManager.getInstance(project).removeDataSource(other)
        assertEquals(1, dir.listDirectoryEntries().size)
        assertEquals(CatalogSource.Bundled, service.store.select(other.uniqueId, service.profile.identity(other)).source)
    }
}
