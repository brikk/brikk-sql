package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import com.intellij.testFramework.PlatformTestUtil
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes
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
        awaitState(listOf("hive"), AutoIntrospectEngine.State.Done)
        assertEquals("memory", pending())
        val newer = nextRefresh()
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

    /** Requests made while one runs all load, none dropped by the platform's queue. */
    fun testEveryQueuedRequestLoads() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val first = nextRefresh()
        assertTrue(service.requestPath(myFixture.file, listOf("memory")))
        assertTrue(service.requestPath(myFixture.file, listOf("q\"t")))
        first.finish { hive.schemas.createOrGet("web") }
        nextRefresh().finish { memory.schemas.createOrGet("tiny") }
        nextRefresh().finish { quoted.schemas.createOrGet("s") }
        awaitIdle()
        for (catalog in listOf("hive", "memory", "q\"t")) awaitState(listOf(catalog), AutoIntrospectEngine.State.Done)
    }

    /** Two requests at once each add their namespace; neither update of the scope is lost. */
    fun testSimultaneousRequestsKeepEveryNamespaceInTheScope() {
        source.gate = CountDownLatch(2)
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        assertTrue(service.requestPath(myFixture.file, listOf("q\"t")))
        nextRefresh().finish { hive.schemas.createOrGet("web") }
        nextRefresh().finish { quoted.schemas.createOrGet("s") }
        awaitIdle()
        source.gate = null
        assertTrue(selects(listOf("hive")))
        assertTrue(selects(listOf("q\"t")))
    }

    /** A refresh that leaves the namespace childless, or never reaches the database, did not load it. */
    fun testARefreshThatLoadsNothingMayRunAgain() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        nextRefresh().finish()
        awaitState(listOf("hive"), AutoIntrospectEngine.State.Failed::class.java)
        assertFalse(service.requestPath(myFixture.file, listOf("hive")))
        time += 1.minutes
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        nextRefresh().finish { hive.schemas.createOrGet("web") }
        awaitState(listOf("hive"), AutoIntrospectEngine.State.Done)
    }

    /** Requests the service can no longer run, queued or made after its scope ended, are released. */
    fun testCancelledRequestsAreReleased() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val running = nextRefresh()
        assertTrue(service.requestPath(myFixture.file, listOf("memory")))
        serviceScope.cancel()
        running.finish()
        service.requestPath(myFixture.file, listOf("q\"t"))
        PlatformTestUtil.waitWithEventsDispatching("the requests are released", {
            listOf("hive", "memory", "q\"t").none { service.engine.state(key(listOf(it))) == AutoIntrospectEngine.State.InFlight }
        }, 10)
        assertNull(pending())
    }

    /** Typed in another case, a namespace is the same request and the same scope entry. */
    fun testTheModelsNamesKeyRequests() {
        assertTrue(service.requestPath(myFixture.file, listOf("HIVE")))
        assertEquals("hive", pending())
        assertFalse(service.requestPath(myFixture.file, listOf("hive")))
        nextRefresh().finish { hive.schemas.createOrGet("web") }
        awaitState(listOf("hive"), AutoIntrospectEngine.State.Done)
        assertEquals(1, source.introspectionScope.root.getGroup(ObjectKind.DATABASE)?.children?.count { it.naming.names.any { name -> name.name.equals("hive", true) } })
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

    private fun key(path: List<String>) = AutoIntrospectEngine.Key(source.uniqueId, path)

    private fun awaitState(path: List<String>, state: AutoIntrospectEngine.State) =
        PlatformTestUtil.waitWithEventsDispatching("$path is $state", { service.engine.state(key(path)) == state }, 10)

    private fun awaitState(path: List<String>, state: Class<out AutoIntrospectEngine.State>) =
        PlatformTestUtil.waitWithEventsDispatching("$path is ${state.simpleName}", { state.isInstance(service.engine.state(key(path))) }, 10)
}
