package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.DataBus
import com.intellij.database.Dbms
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.connection.throwable.info.ErrorInfo
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
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
import java.util.concurrent.CopyOnWriteArrayList
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
    private inner class Console(val virtualFile: VirtualFile, val session: DatabaseSession, val console: JdbcConsole) {
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
    private val finished = CopyOnWriteArrayList<DataRequest>()
    private val executionErrors = CopyOnWriteArrayList<String>()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        dir = Path.of(PathManager.getSystemPath(), "live-catalog-console", UUID.randomUUID().toString())
        dataSource = realDuckdbDataSource(project, testRootDisposable)
        // A console is valid only while its data source is in the PSI view, which caches the data sources.
        DbPsiFacade.getInstance(project).clearCaches()
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
            override fun error(context: DataRequest.Context, error: ErrorInfo) {
                executionErrors += error.toString()
            }

            override fun requestFinished(context: DataRequest.Context) {
                finished += context.request
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
        assertTrue(console.isValid)
        return Console(file.virtualFile, session, console).also { consoles += it }
    }

    /**
     * Runs [sql] from the console's editor, as Execute does: an editor-backed console request, which the
     * platform (263 and later) also reads for transaction control statements.
     */
    private fun Console.run(sql: String) {
        submit(sql)
        assertEmpty(executionErrors)
    }

    /** Runs [sql], which must fail, from the console's editor. */
    private fun Console.runFailing(sql: String) {
        submit(sql)
        assertFalse("'$sql' fails", executionErrors.isEmpty())
        executionErrors.clear()
    }

    /** Runs the script [sql] as one request, which must fail. */
    private fun Console.runScriptFailing(sql: String) {
        val request = FinishedQuery(console, sql)
        session.messageBus.dataProducer.processRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("'$sql' finishes", { request in finished }, 30)
        assertFalse("'$sql' fails", executionErrors.isEmpty())
        executionErrors.clear()
    }

    private fun Console.submit(sql: String) {
        myFixture.configureFromExistingVirtualFile(virtualFile)
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.setText(sql) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        FileDocumentManager.getInstance().saveDocument(document)
        val request = checkNotNull(ConsoleDataRequest.newConsoleRequest(console, myFixture.editor, console.scriptModel, false))
        session.messageBus.dataProducer.processRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("'$sql' finishes", { request in finished }, 30)
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

    /**
     * A COMMIT statement ends a manual-commit transaction, but a manual-commit session is never harvested.
     * Back in auto-commit mode, the observer keeps nothing stale: where the platform has a transaction control
     * handler for the dbms (263 and later, PostgreSQL and MySQL families), it sees the COMMIT too and the
     * session is harvested; otherwise the platform still holds the transaction it did not see end and refuses
     * the harvest's request, so nothing is read.
     */
    @Test
    fun aCommitStatementInManualCommitModeLeavesNoTransactionOfTheObserverBehind() {
        val a = console("a")
        a.run("CREATE TABLE written(x INT)")
        val harvests = harvester.harvests.get()
        a.session.setAutoCommit(false)
        a.run("INSERT INTO written VALUES (1)")
        a.run("COMMIT")
        assertEquals(RefreshOutcome.SessionInTransaction, refreshFrom(a.file))
        assertEquals(harvests, harvester.harvests.get())
        a.session.setAutoCommit(true)
        val outcome = refreshFrom(a.file)
        if (platformSeesTransactionControl(dataSource.dbms)) {
            assertInstanceOf(outcome, RefreshOutcome.Refreshed::class.java)
            assertEquals(harvests + 1, harvester.harvests.get())
        } else {
            assertInstanceOf(outcome, RefreshOutcome.HarvestFailed::class.java)
            assertEquals(harvests, harvester.harvests.get())
        }
    }

    @Test
    fun aFailedBeginBeginsNothing() {
        val a = console("a")
        a.run("SELECT 1")
        a.runFailing("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE")
        assertInstanceOf(refreshFrom(a.file), RefreshOutcome.Refreshed::class.java)
    }

    @Test
    fun aFailedEndKeepsTheTransaction() {
        val a = console("a")
        a.run("BEGIN TRANSACTION")
        a.runFailing("COMMIT nonsense")
        assertEquals(RefreshOutcome.SessionInTransaction, refreshFrom(a.file))
        a.run("ROLLBACK")
        assertInstanceOf(refreshFrom(a.file), RefreshOutcome.Refreshed::class.java)
    }

    /** The script stops at its failing statement, so its COMMIT never runs; the ROLLBACK proves the transaction is open. */
    @Test
    fun aScriptFailingBeforeItsCommitLeavesTheTransactionOpen() {
        val a = console("a")
        a.run("SELECT 1")
        a.runScriptFailing("BEGIN TRANSACTION; SELECT * FROM missing; COMMIT")
        assertEquals(RefreshOutcome.SessionInTransaction, refreshFrom(a.file))
        a.run("ROLLBACK")
        assertInstanceOf(refreshFrom(a.file), RefreshOutcome.Refreshed::class.java)
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

    /** Whether the platform reads COMMIT and ROLLBACK statements in consoles of [dbms] (263 and later). */
    private fun platformSeesTransactionControl(dbms: Dbms): Boolean = try {
        Class.forName("com.intellij.database.console.TransactionControlKt", true, ConsoleDataRequest::class.java.classLoader)
            .getMethod("getTransactionControlHandler", Dbms::class.java).invoke(null, dbms) != null
    } catch (_: ClassNotFoundException) {
        false
    }
}
