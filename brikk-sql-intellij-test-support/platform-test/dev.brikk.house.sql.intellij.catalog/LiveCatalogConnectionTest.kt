package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * The service and its interceptor, registered as a plugin registers them, over real IDE connections:
 * a DuckDB file reached through the IDE's remote JDBC process.
 */
class LiveCatalogConnectionTest : BasePlatformTestCase() {
    class Interceptor : HarvestConnectionInterceptor(LiveService::class.java)

    private lateinit var errors: BackgroundErrors
    private lateinit var dir: Path
    private lateinit var dataSource: LocalDataSource
    private lateinit var harvester: CountingHarvester
    private lateinit var service: LiveService

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        dir = Path.of(PathManager.getSystemPath(), "live-catalog-connection", UUID.randomUUID().toString())
        dataSource = realDuckdbDataSource(project, testRootDisposable)
        harvester = CountingHarvester()
        useProfile(LiveProfile(harvester, dbms = dataSource.dbms))
        ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<DatabaseConnectionInterceptor>("com.intellij.database.connectionInterceptor")
            .registerExtension(Interceptor(), testRootDisposable)
    }

    override fun tearDown() = RunAll(
        { errors.awaitScopes() },
        { errors.assertNone() },
        { dir.toFile().deleteRecursively() },
        { super.tearDown() },
    ).run()

    private fun useProfile(profile: LiveCatalogProfile) {
        service = LiveService(errors.newScope(testRootDisposable), profile, dir)
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(LiveService::class.java, service, testRootDisposable)
    }

    fun testARefreshHarvestsOnceOverItsOwnConnection() {
        val outcome = runBlocking { service.refresh(project, dataSource) }
        assertInstanceOf(outcome, RefreshOutcome.Refreshed::class.java)
        assertEquals("the interceptor skips the refresh's connection", 1, harvester.harvests.get())
        val selection = service.selection(dataSource)
        assertInstanceOf(selection.source, CatalogSource.Live::class.java)
        assertTrue(selection.inventory?.functions.orEmpty().any { it.def.name == "duckdb_functions" })
    }

    fun testANewConnectionHarvestsOncePerSessionThroughTheInterceptor() {
        withIdeConnection(project, dataSource, otherRequestor()) { }
        assertEquals(1, harvester.harvests.get())
        assertInstanceOf(service.selection(dataSource).source, CatalogSource.Live::class.java)
        withIdeConnection(project, dataSource, otherRequestor()) { }
        assertEquals(1, harvester.harvests.get())
    }

    fun testAFailedRefreshKeepsTheValidSnapshot() {
        withIdeConnection(project, dataSource, otherRequestor()) { }
        val before = service.selection(dataSource).inventory
        harvester.answer = { rows -> rows.forEachRow("SELECT * FROM no_such_table") { }; null }
        val outcome = runBlocking { service.refresh(project, dataSource) }
        assertInstanceOf((outcome as RefreshOutcome.HarvestFailed).outcome, HarvestOutcome.Failed::class.java)
        assertSame(before, service.selection(dataSource).inventory)
    }

    fun testOtherDbmsConnectionsAreNotIntercepted() {
        useProfile(LiveProfile(harvester, dbms = Dbms.POSTGRES))
        withIdeConnection(project, dataSource, otherRequestor()) { }
        assertEquals(0, harvester.harvests.get())
    }
}
