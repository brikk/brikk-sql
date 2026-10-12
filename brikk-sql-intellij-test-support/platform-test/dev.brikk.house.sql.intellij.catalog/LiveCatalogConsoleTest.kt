package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.DataBus
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * Real consoles on a real DuckDB data source: statements run through each console's own session and
 * engine, the registered interceptor sees their connections, and the observer their finished requests.
 */
class LiveCatalogConsoleTest : BasePlatformTestCase() {
    private class Console(val file: PsiFile, val session: DatabaseSession, val console: JdbcConsole)

    private lateinit var errors: BackgroundErrors
    private lateinit var dir: Path
    private lateinit var dataSource: LocalDataSource
    private lateinit var harvester: CountingHarvester
    private lateinit var service: LiveService
    private val consoles = ArrayList<Console>()
    private val finished = AtomicInteger()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        dir = Path.of(PathManager.getSystemPath(), "live-catalog-console", UUID.randomUUID().toString())
        dataSource = realDuckdbDataSource(project, testRootDisposable)
        harvester = CountingHarvester()
        service = LiveService(errors.newScope(testRootDisposable), LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT, dbms = dataSource.dbms), dir)
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(LiveService::class.java, service, testRootDisposable)
        ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<DatabaseConnectionInterceptor>("com.intellij.database.connectionInterceptor")
            .registerExtension(LiveInterceptor(), testRootDisposable)
        val profile = ExecutionObserverTest.Profile(dbms = dataSource.dbms, liveCatalog = LiveService::class.java, autoIntrospect = null,
            rules = StatementRules.DUCKDB, dialect = "duckdb")
        val observer = ExecutionObserverTest.Observer(project, errors.newScope(testRootDisposable), profile)
        Disposer.register(testRootDisposable, observer)
        observer.start()
        // Registered after the observer, so a request counts as finished once the observer handled it.
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun requestFinished(context: DataRequest.Context) {
                finished.incrementAndGet()
            }
        }, testRootDisposable)
    }

    override fun tearDown() = RunAll(
        { consoles.forEach { DatabaseSessionViewService.getInstance().destroyView(it.console); Disposer.dispose(it.console); Disposer.dispose(it.session) } },
        { errors.awaitScopes() },
        { errors.assertNone() },
        { dir.toFile().deleteRecursively() },
        { super.tearDown() },
    ).run()

    private fun console(name: String): Console {
        val file = myFixture.addFileToProject("$name-${UUID.randomUUID()}.sql", "SELECT 1")
        val session = DatabaseSessionManager.openSession(project, dataSource, "$name ${UUID.randomUUID()}")
        val console = JdbcConsole.newConsole(project).forFile(file.virtualFile).fromDataSource(dataSource).useSession(session).build()
        return Console(file, session, console).also { consoles += it }
    }

    private fun Console.run(sql: String) {
        val before = finished.get()
        session.messageBus.dataProducer.processRequest(FinishedQuery(console, sql))
        PlatformTestUtil.waitWithEventsDispatching("'$sql' finishes", { finished.get() > before }, 30)
    }

    private fun Console.sessionFunctions() = service.completion(file).selection.session.functions.map { it.def.name }

    fun testTwoConsolesKeepTheirOwnTemporaryMacrosThroughARefresh() {
        val a = console("a")
        a.run("CREATE TEMP MACRO only_in_a(x) AS x + 1")
        val b = console("b")
        b.run("CREATE TEMP MACRO only_in_b(x) AS x + 2")
        assertEquals(listOf("only_in_a"), a.sessionFunctions())
        assertEquals(listOf("only_in_b"), b.sessionFunctions())
        assertInstanceOf(runBlocking { service.refresh(project, dataSource) }, RefreshOutcome.Refreshed::class.java)
        assertEquals("a refresh keeps each session's facts", listOf("only_in_a"), a.sessionFunctions())
        assertEquals(listOf("only_in_b"), b.sessionFunctions())
        assertTrue("only_in_a" in service.completion(a.file).functions.map { it.name })
        assertFalse("only_in_b" in service.completion(a.file).functions.map { it.name })
    }

    /** Needs the `fts` extension installed for the user (`INSTALL fts` once); the test loads it, offline. */
    fun testAnExtensionASessionLoadedSurvivesARefresh() {
        val a = console("a")
        a.run("LOAD fts")
        assertTrue("stem" in a.sessionFunctions())
        assertEquals(listOf("fts"), service.completion(a.file).selection.session.extensions)
        assertInstanceOf(runBlocking { service.refresh(project, dataSource) }, RefreshOutcome.Refreshed::class.java)
        assertTrue("the refresh keeps the session's extension functions", "stem" in a.sessionFunctions())
        assertFalse("they are not the server's", service.completion(a.file).selection.inventory?.functions.orEmpty().any { it.def.name == "stem" })
    }
}
