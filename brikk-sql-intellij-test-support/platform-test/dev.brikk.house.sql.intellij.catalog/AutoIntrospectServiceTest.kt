package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.postgres.model.PgDatabase
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.Casing
import com.intellij.database.util.LoaderContext
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AutoIntrospectServiceTest : AutoIntrospectTestCase() {
    class Banner : IntrospectBannerProvider(Service::class.java)

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("introspect-${UUID.randomUUID()}.sql", "SELECT * FROM hive.")
    }

    fun testAChildlessCatalogIsIntrospectedOneLevel() {
        var updates = 0
        project.messageBus.connect(testRootDisposable).subscribe(DataSourceStorage.TOPIC, object : DataSourceStorage.Listener {
            override fun dataSourceChanged(dataSource: LocalDataSource?, configurationChanged: Boolean) {
                if (dataSource === source) updates++
            }
        })
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val refresh = nextRefresh()
        assertSame(source, refresh.context.dataSource)
        assertEquals("only the catalog is refreshed", 1, refresh.context.tasks.size)
        assertEquals("hive", pending())
        assertTrue("the scope gains the catalog", selects(listOf("hive")))
        assertTrue("and keeps what it selected", selects(listOf("memory")))
        assertNull("one level only", scopeNode(listOf("hive"))?.getGroup(ObjectKind.SCHEMA))
        assertTrue("the data source is saved", updates > 0)
        assertFalse("claimed while in flight", service.requestPath(myFixture.file, listOf("hive")))
        refresh.finish { hive.schemas.createOrGet("web") }
        awaitIdle()
        assertNull(pending())
        assertEquals(AutoIntrospectEngine.State.Done, service.engine.state(AutoIntrospectEngine.Key(source.uniqueId, listOf("hive"))))
        assertFalse(service.requestPath(myFixture.file, listOf("hive")))
    }

    fun testAFailedRefreshMayRunAgainAfterTheDelay() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        nextRefresh().finish { throw java.sql.SQLException("offline") }
        awaitIdle()
        assertNull(pending())
        assertInstanceOf(service.engine.state(AutoIntrospectEngine.Key(source.uniqueId, listOf("hive"))), AutoIntrospectEngine.State.Failed::class.java)
        assertFalse(service.requestPath(myFixture.file, listOf("hive")))
        time += 1.minutes
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        nextRefresh().finish { hive.schemas.createOrGet("web") }
        awaitIdle()
    }

    fun testListedChildrenAndUnknownNamesStartNothing() {
        assertFalse(service.requestPath(myFixture.file, listOf("loaded")))
        assertFalse(service.requestPath(myFixture.file, listOf("nope")))
        assertFalse("deeper than the namespaces", service.requestPath(myFixture.file, listOf("loaded", "web", "orders")))
        assertNoRefresh()
    }

    fun testOffUntilTheDialectEnablesIt() {
        useService(Profile(autoIntrospect = false))
        assertFalse(service.requestPath(myFixture.file, listOf("hive")))
        assertNoRefresh()
    }

    fun testAnOlderRequestCannotClearANewerBanner() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val older = nextRefresh()
        assertTrue(service.requestPath(myFixture.file, listOf("memory")))
        assertEquals("memory", pending())
        older.finish { hive.schemas.createOrGet("web") }
        val newer = nextRefresh()
        assertEquals("memory", pending())
        newer.finish { memory.schemas.createOrGet("tiny") }
        awaitIdle()
        assertNull(pending())
        for (catalog in listOf("hive", "memory")) {
            assertEquals(catalog, AutoIntrospectEngine.State.Done, service.engine.state(AutoIntrospectEngine.Key(source.uniqueId, listOf(catalog))))
        }
    }

    fun testRelativePathsUseTheConsoleNamespace() {
        useService(Profile(usesCurrentNamespace = true))
        RecordingConsole(project, myFixture.file.virtualFile, "postgresql").use { recording ->
            val model = ModelFactory.BLACK_HOLE.createModel(Dbms.POSTGRES)
            val web = (model.root as PgRoot).databases.createOrGet("hive").schemas.createOrGet("web")
            recording.console.dataSource.model = model
            DbPsiFacade.getInstance(project).clearCaches()
            assertFalse("not in the current catalog yet", service.requestPath(myFixture.file, listOf("web")))
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("hive", ObjectKind.DATABASE)))
            assertTrue(service.requestPath(myFixture.file, listOf("web")))
            val refresh = nextRefresh()
            assertSame(recording.console.dataSource, refresh.context.dataSource)
            assertEquals("hive.web", pending())
            refresh.finish { web.tables.createOrGet("orders") }
            awaitIdle()
        }
    }

    /** A dialect may reach below its namespaces: a table without columns refreshes itself, its schema stays the scope. */
    fun testATableWithoutColumnsRefreshesItself() {
        useService(Profile(maxDeepenDepth = 3))
        assertTrue(service.requestPath(myFixture.file, listOf("loaded", "web", "orders")))
        val refresh = nextRefresh()
        assertEquals("loaded.web.orders", pending())
        assertNotNull(scopeNode(listOf("loaded", "web")))
        refresh.finish { orders.columns.createOrGet("id") }
        awaitIdle()
    }

    fun testDismissHidesTheBanner() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val refresh = nextRefresh()
        assertNotNull(Banner().collectNotificationData(project, myFixture.file.virtualFile))
        service.dismiss(myFixture.file.virtualFile)
        assertNull(Banner().collectNotificationData(project, myFixture.file.virtualFile))
        refresh.finish()
        awaitIdle()
    }
}
