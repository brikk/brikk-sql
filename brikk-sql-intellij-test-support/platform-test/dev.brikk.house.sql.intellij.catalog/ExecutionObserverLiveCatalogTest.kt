package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Function inventory statements: the live catalog harvests again over the session's own connection. */
class ExecutionObserverLiveCatalogTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var dir: Path
    private lateinit var harvester: CountingHarvester
    private lateinit var catalog: LiveService
    private lateinit var recording: RecordingConsole
    private lateinit var jdbc: Connection
    private val engine = LiveCatalogServiceTest.Engine()
    private val held = ArrayList<Any>()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        dir = Path.of(PathManager.getSystemPath(), "observer-live", UUID.randomUUID().toString())
        harvester = CountingHarvester()
        catalog = LiveService(errors.newScope(testRootDisposable), LiveProfile(harvester, HarvestTrigger.EVERY_CONSOLE_CONNECT), dir)
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(LiveService::class.java, catalog, testRootDisposable)
        myFixture.configureByText("observe-${UUID.randomUUID()}.sql", "SELECT 1")
        recording = RecordingConsole(project, myFixture.file.virtualFile, "postgresql")
        jdbc = embeddedDuckdb()
        runBlocking { catalog.connected(databaseConnection(jdbc, recording.console.dataSource, Dbms.POSTGRES, engine).also { held += it }) }
    }

    override fun tearDown() = RunAll(
        { errors.awaitScopes() },
        { errors.assertNone() },
        { jdbc.close() },
        { recording.close() },
        { dir.toFile().deleteRecursively() },
        { super.tearDown() },
    ).run()

    private fun observer(rules: List<StatementRule> = StatementRules.DUCKDB) =
        ExecutionObserverTest.Observer(project, errors.newScope(testRootDisposable),
            ExecutionObserverTest.Profile(liveCatalog = LiveService::class.java, autoIntrospect = null, rules = rules))

    private fun offered() = catalog.completion(myFixture.file).functions.map { it.name }

    fun testATemporaryMacroIsOfferedToItsSessionAfterItRan() {
        jdbc.createStatement().use { it.execute("CREATE TEMP MACRO add_one(x) AS x + 1") }
        assertFalse("add_one" in offered())
        observer().requestFinished(finished(recording.console, engine, "CREATE TEMP MACRO add_one(x) AS x + 1"))
        assertEquals(2, harvester.harvests.get())
        assertTrue("add_one" in offered())
    }

    fun testOtherStatementsHarvestNothing() {
        observer().requestFinished(finished(recording.console, engine, "SELECT 'LOAD spatial'; CREATE TABLE t(x INT)"))
        observer(rules = emptyList()).requestFinished(finished(recording.console, engine, "LOAD spatial"))
        assertEquals(1, harvester.harvests.get())
    }
}
