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
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Real consoles on a real DuckDB data source: statements run through each console's own session and
 * engine, the registered interceptor sees their connections, and the observer their finished requests.
 * JUnit 4, so a test can skip on an assumption.
 */
@RunWith(JUnit4::class)
class LiveCatalogConsoleTest : BasePlatformTestCase() {
    private inner class Console(private val virtualFile: VirtualFile, val session: DatabaseSession, val console: JdbcConsole) {
        /** The file's current PSI: executing statements may reparse it. */
        val file: PsiFile get() = checkNotNull(PsiManager.getInstance(project).findFile(virtualFile))
    }

    class Refresh : RefreshLiveCatalogAction(LiveService::class.java)

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
        return Console(file.virtualFile, session, console).also { consoles += it }
    }

    private fun Console.run(sql: String) {
        val before = finished.get()
        session.messageBus.dataProducer.processRequest(FinishedQuery(console, sql))
        PlatformTestUtil.waitWithEventsDispatching("'$sql' finishes", { finished.get() > before }, 30)
    }

    private fun Console.sessionFunctions() = service.completion(file).selection.session.functions.map { it.def.name }

    @Test
    fun twoConsolesKeepTheirOwnTemporaryMacrosThroughARefresh() {
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

    /** The extension tests need `fts` installed for the user (`INSTALL fts` once), and skip without; they load it offline. */
    private fun assumeFts() {
        val installed = embeddedDuckdb().use { engine ->
            engine.createStatement().use { it.executeQuery("SELECT installed FROM duckdb_extensions() WHERE extension_name = 'fts'").use { rows -> rows.next() && rows.getBoolean(1) } }
        }
        Assume.assumeTrue("the fts extension is not installed for this user", installed)
    }

    /** Refreshes from [file]'s editor, as the user does, and returns what the notification received. */
    private fun refreshFrom(file: PsiFile): RefreshOutcome {
        val before = service.refreshed.size
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, file).build()
        Refresh().actionPerformed(TestActionEvent.createTestEvent(context))
        PlatformTestUtil.waitWithEventsDispatching("the refresh ends", { service.refreshed.size > before }, 30)
        return service.refreshed.last()
    }

    @Test
    fun aRefreshFromAConsoleReadsTheExtensionsItsSessionAutoloaded() {
        assumeFts()
        val b = console("b")
        b.run("SELECT 1")
        val a = console("a")
        a.run("SELECT stem('running', 'porter')")
        assertFalse("autoload runs no statement the observer knows", "stem" in a.sessionFunctions())
        val outcome = refreshFrom(a.file) as RefreshOutcome.Refreshed
        assertTrue("the notification sees the session's functions", outcome.session.functions.any { it.def.name == "stem" })
        assertEquals(listOf("fts"), outcome.session.extensions)
        assertTrue("stem" in a.sessionFunctions())
        assertTrue("stem" in service.completion(a.file).functions.filter { it.extension == null }.map { it.name })
        assertFalse("another session does not get them", "stem" in b.sessionFunctions())
    }

    @Test
    fun aRefreshFromAConsoleInATransactionReadsNothing() {
        val a = console("a")
        a.run("CREATE TABLE written(x INT)")
        val harvests = harvester.harvests.get()
        // Manual commit: a change opens a transaction that stays open until committed.
        a.session.setAutoCommit(false)
        a.run("INSERT INTO written VALUES (1)")
        assertEquals(RefreshOutcome.SessionInTransaction, refreshFrom(a.file))
        assertEquals(harvests, harvester.harvests.get())
    }

    /** The platform does not see a transaction a statement began in auto-commit mode; the observer does. */
    @Test
    fun aRefreshFromAConsoleInATransactionItBeganReadsNothingUntilItEnds() {
        val a = console("a")
        a.run("SELECT 1")
        val harvests = harvester.harvests.get()
        a.run("BEGIN TRANSACTION")
        assertEquals(RefreshOutcome.SessionInTransaction, refreshFrom(a.file))
        assertEquals(harvests, harvester.harvests.get())
        a.run("COMMIT")
        assertInstanceOf(refreshFrom(a.file), RefreshOutcome.Refreshed::class.java)
        assertEquals(harvests + 1, harvester.harvests.get())
    }

    @Test
    fun aRefreshFromAnEditorWithoutAConnectedSessionUsesAHelperConnection() {
        val a = console("a")
        val outcome = refreshFrom(a.file) as RefreshOutcome.Refreshed
        assertEquals(SessionFacts.NONE, outcome.session)
        assertFalse(a.session.isConnected)
    }

    @Test
    fun anExtensionASessionLoadedSurvivesARefresh() {
        assumeFts()
        val a = console("a")
        a.run("LOAD fts")
        assertTrue("stem" in a.sessionFunctions())
        assertEquals(listOf("fts"), service.completion(a.file).selection.session.extensions)
        assertInstanceOf(runBlocking { service.refresh(project, dataSource) }, RefreshOutcome.Refreshed::class.java)
        assertTrue("the refresh keeps the session's extension functions", "stem" in a.sessionFunctions())
        assertFalse("they are not the server's", service.completion(a.file).selection.inventory?.functions.orEmpty().any { it.def.name == "stem" })
    }
}
