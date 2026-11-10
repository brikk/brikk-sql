package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.database.dataSource.DataSourceModelStorage
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.DasObject
import com.intellij.database.model.basic.BasicModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.TreePattern
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

class AutoIntrospectScopeRollbackTest : AutoIntrospectTestCase() {
    class Filter : OutOfScopeReferenceFilter(Service::class.java)

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("rollback-${UUID.randomUUID()}.sql", "SELECT * FROM hive.web.orders")
    }

    private fun publishedScopes(): List<TreePattern> = CopyOnWriteArrayList<TreePattern>().also { scopes ->
        project.messageBus.connect(testRootDisposable).subscribe(DataSourceStorage.TOPIC, object : DataSourceStorage.Listener {
            override fun dataSourceChanged(dataSource: LocalDataSource?, configurationChanged: Boolean) {
                if (dataSource === source) scopes += source.introspectionScope
            }
        })
    }

    // Platform tests compile separately: exercise the internal decision without adding public API.
    private fun introspected(path: List<String>): Boolean = AutoIntrospectService::class.java.methods
        .single { it.name.startsWith("introspected\$") }.invoke(service, source, path) as Boolean

    private fun withFailingRollbackPublication(before: TreePattern, operation: () -> Unit) {
        val original = DataSourceStorage.getProjectStorage(project)
        val models = DataSourceModelStorage.getStorage(project)
        val failure = IllegalStateException("storage failed while publishing rollback")
        val thrown = AtomicBoolean()
        val storage = object : DataSourceStorage(project.messageBus) {
            override fun getModelStorage() = models
            override fun getOwnDataSources() = original.ownDataSources
            override fun getDataSources() = original.dataSources
            override fun getDataSourcesImpl() = original.dataSourcesImpl
            override fun updateDataSource(dataSource: LocalDataSource) {
                if (dataSource === source && dataSource.introspectionScope == before && thrown.compareAndSet(false, true)) throw failure
                original.updateDataSource(dataSource)
            }
        }
        val replacement = Disposer.newDisposable(testRootDisposable, "throwing rollback storage")
        project.registerOrReplaceServiceInstance(DataSourceStorage::class.java, storage, replacement)
        val warnings = CopyOnWriteArrayList<Throwable?>()
        val logging = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                if (category == "#dev.brikk.house.sql.intellij.catalog.AutoIntrospectService" && message.startsWith("Restoring the introspection scope")) warnings += t
                return super.processWarn(category, message, t)
            }
        })
        try {
            operation()
            assertTrue("rollback reached the throwing storage", thrown.get())
            assertEquals("the storage exception is logged at WARN", listOf(failure), warnings)
        } finally {
            logging.finish()
            Disposer.dispose(replacement)
        }
    }

    fun testAFailedLoadRestoresAndPublishesThePreviousScope() {
        val before = source.introspectionScope
        val published = publishedScopes()
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val refresh = nextRefresh()
        val widened = source.introspectionScope
        assertTrue(selects(listOf("hive")))
        assertEquals(widened, published.last())
        val updates = published.size
        refresh.finish { throw SQLException("offline") }
        awaitIdle()
        assertEquals(before, source.introspectionScope)
        assertTrue("the rollback is published", published.size > updates)
        assertEquals(before, published.last())
        assertFalse(selects(listOf("hive")))
        assertTrue(selects(listOf("memory")) && selects(listOf("loaded", "web")))
    }

    fun testAPartlyLoadedNamespaceKeepsItsObjectsAfterAFailedLoad() {
        val schema = ((source.model as BasicModel).root as PgRoot).databases.createOrGet("part").schemas.createOrGet("s")
        schema.tables.createOrGet("t1")
        DbPsiFacade.getInstance(project).clearCaches()
        val before = source.introspectionScope
        assertFalse(selects(listOf("part", "s")))
        assertEquals(listOf("t1"), schema.tables.map { it.name })
        assertTrue(service.requestPath(myFixture.file, listOf("part", "s")))
        val refresh = nextRefresh()
        assertTrue(selects(listOf("part", "s")))
        refresh.finish { throw SQLException("offline") }
        awaitIdle()
        assertEquals(before, source.introspectionScope)
        assertEquals("rollback must not hide objects loaded before the request", listOf("t1"), schema.tables.map { it.name })
    }

    fun testAFailedLoadsPartialObjectsStayVisibleOutsideTheScopeWithoutMarkingItIntrospected() {
        val before = source.introspectionScope
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        nextRefresh().finish {
            hive.schemas.createOrGet("web").tables.createOrGet("partial")
            throw SQLException("failed after writing partial content")
        }
        awaitIdle()
        assertEquals(before, source.introspectionScope)
        assertFalse(selects(listOf("hive")))
        assertEquals(listOf("partial"), hive.schemas.single().tables.map { it.name })
        assertFalse(introspected(listOf("hive")))
        useService(Profile())
        assertFalse("a fresh service also knows no successful load", introspected(listOf("hive")))
        assertTrue("partial children do not make the namespace complete", service.requestPath(myFixture.file, listOf("hive")))
        nextRefresh().finish()
        awaitIdle()
    }

    fun testCancellationWhileCheckingACompletedLoadDoesNotRollBackItsScope() {
        val synced = AtomicBoolean()
        val checked = AtomicBoolean()
        useService(object : CatalogProfile by Profile() {
            override fun childrenOf(node: DasObject): List<DasObject> {
                if (node.name == "hive" && synced.get()) {
                    checked.set(true)
                    serviceScope.cancel()
                    throw CancellationException("cancel the post-sync model check")
                }
                return NamespacePathResolver.loadedChildren(node, levels)
            }
        })
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val refresh = nextRefresh()
        val widened = source.introspectionScope
        refresh.finish { synced.set(true) }
        awaitIdle()
        assertTrue("cancellation happened inside the post-sync read action", checked.get())
        assertEquals(widened, source.introspectionScope)
        assertTrue("the completed sync stays recorded", introspected(listOf("hive")))
    }

    fun testAThrowingRollbackPublicationDoesNotStopQueuedLoads() {
        val before = source.introspectionScope
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val running = nextRefresh()
        withFailingRollbackPublication(before) {
            assertTrue(service.requestPath(myFixture.file, listOf("memory")))
            running.finish { throw SQLException("offline") }
            nextRefresh().finish { memory.schemas.createOrGet("tiny") }
            awaitIdle()
            assertEquals(before, source.introspectionScope)
            assertEquals(AutoIntrospectEngine.State.Done, service.engine.state(AutoIntrospectEngine.Key(source.uniqueId, listOf("memory"))))
        }
    }

    fun testAThrowingRollbackPublicationPreservesTheOriginalCancellation() {
        val before = source.introspectionScope
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val running = nextRefresh()
        val worker = serviceScope.coroutineContext.job.children.single()
        val outcome = AtomicReference<Throwable?>()
        worker.invokeOnCompletion { outcome.set(it) }
        val cancellation = CancellationException("cancel the running request")
        withFailingRollbackPublication(before) {
            worker.cancel(cancellation)
            running.finish()
            PlatformTestUtil.waitWithEventsDispatching("the cancelled worker finishes", { worker.isCompleted && outcome.get() != null }, 10)
            assertSame(cancellation, outcome.get())
            assertEquals(before, source.introspectionScope)
            assertNull(pending())
        }
    }

    fun testACancelledLoadRestoresAndPublishesThePreviousScopeAndLeavesQueuedScopesAlone() {
        val before = source.introspectionScope
        val published = publishedScopes()
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val running = nextRefresh()
        assertTrue(selects(listOf("hive")))
        assertTrue(service.requestPath(myFixture.file, listOf("q\"t")))
        val updates = published.size
        serviceScope.cancel()
        running.finish()
        PlatformTestUtil.waitWithEventsDispatching("running and queued requests finish", {
            listOf("hive", "q\"t").all {
                service.engine.state(AutoIntrospectEngine.Key(source.uniqueId, listOf(it))) is AutoIntrospectEngine.State.Failed
            }
        }, 10)
        assertNull(pending())
        assertEquals(before, source.introspectionScope)
        assertTrue("cancellation does not prevent publishing", published.size > updates)
        assertEquals(before, published.last())
        assertFalse(selects(listOf("q\"t")))
        assertNoRefresh()
    }

    fun testASuccessfulLoadKeepsTheWidenedScope() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val refresh = nextRefresh()
        val widened = source.introspectionScope
        refresh.finish { hive.schemas.createOrGet("web") }
        awaitIdle()
        assertEquals(widened, source.introspectionScope)
        assertTrue(selects(listOf("hive")))
    }

    fun testASuccessfulEmptyLoadKeepsTheWidenedScope() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val refresh = nextRefresh()
        val widened = source.introspectionScope
        refresh.finish()
        awaitIdle()
        assertEquals(widened, source.introspectionScope)
        assertTrue(selects(listOf("hive")))
    }

    fun testAFailedLoadDoesNotOverwriteAScopeEditedWhileItRan() {
        val published = publishedScopes()
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val refresh = nextRefresh()
        val edited = CatalogScopes.widen(source.introspectionScope, CatalogScopes.select(levels(), listOf("q\"t")))
        source.introspectionScope = edited
        DataSourceStorage.getProjectStorage(project).updateDataSource(source)
        val updates = published.size
        refresh.finish { throw SQLException("offline") }
        awaitIdle()
        assertSame(edited, source.introspectionScope)
        assertEquals("no rollback is published over the edit", updates, published.size)
        assertTrue(selects(listOf("q\"t")))
    }

    fun testAnEquivalentScopeIsComparedByValueBeforeRollback() {
        val before = source.introspectionScope
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val refresh = nextRefresh()
        val equivalent = CatalogScopes.widen(before, CatalogScopes.select(levels(), listOf("hive")))
        assertNotSame(source.introspectionScope, equivalent)
        assertEquals(source.introspectionScope, equivalent)
        source.introspectionScope = equivalent
        refresh.finish { throw SQLException("offline") }
        awaitIdle()
        assertEquals(before, source.introspectionScope)
    }

    fun testAFailedLoadOfAnAlreadySelectedNamespaceRollsBackNothing() {
        val before = source.introspectionScope
        val published = publishedScopes()
        assertTrue(service.requestPath(myFixture.file, listOf("memory")))
        val refresh = nextRefresh()
        val written = source.introspectionScope
        assertEquals(before, written)
        val updates = published.size
        refresh.finish { throw SQLException("offline") }
        awaitIdle()
        assertSame(written, source.introspectionScope)
        assertEquals("no rollback is published", updates, published.size)
    }

    fun testAFailedDialectListingChangesAndRollsBackNothing() {
        useService(Profile()) { _, _, _ -> false }
        val before = source.introspectionScope
        val published = publishedScopes()
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        awaitIdle()
        assertSame(before, source.introspectionScope)
        assertEmpty(published)
        assertNoRefresh()
    }

    fun testAskingAgainAfterRollbackWidensAndLoadsAgain() {
        val before = source.introspectionScope
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        nextRefresh().finish { throw SQLException("offline") }
        awaitIdle()
        assertEquals(before, source.introspectionScope)
        time += 1.minutes
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        val retry = nextRefresh()
        assertTrue(selects(listOf("hive")))
        retry.finish { hive.schemas.createOrGet("web") }
        awaitIdle()
        assertEquals(AutoIntrospectEngine.State.Done, service.engine.state(AutoIntrospectEngine.Key(source.uniqueId, listOf("hive"))))
    }

    fun testAFailedRefreshOfPreviouslyLoadedDataDoesNotRollBackItsScope() {
        assertTrue(service.requestPath(myFixture.file, listOf("hive")))
        nextRefresh().finish { hive.schemas.createOrGet("web") }
        awaitIdle()
        val before = source.introspectionScope
        val published = publishedScopes()
        assertTrue(service.refreshChanged(source, listOf("hive")))
        nextRefresh().finish { throw SQLException("offline") }
        // refreshChanged has no banner: wait for the next queued refresh to reach the boundary.
        assertTrue(service.refreshChanged(source, listOf("hive")))
        nextRefresh().finish()
        assertSame(before, source.introspectionScope)
        assertEmpty(published)
    }

    fun testANewServiceOverTheRolledBackScopeDoesNotTreatAnUnloadedNamespaceAsIntrospected() {
        useService(Profile(outOfScopeReferences = true))
        setSqlDialectMapping(project, myFixture.file.virtualFile, PgDialect.INSTANCE, testRootDisposable)
        RecordingConsole(project, myFixture.file.virtualFile, source).use {
            HighlightInfoFilter.EXTENSION_POINT_NAME.point.registerExtension(Filter(), testRootDisposable)
            val inspection = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass.endsWith(".SqlResolveInspection") }
            myFixture.enableInspections(inspection.instantiateTool() as LocalInspectionTool)
            val before = source.introspectionScope
            assertTrue(service.requestPath(myFixture.file, listOf("hive")))
            val refresh = nextRefresh()
            val widened = source.introspectionScope
            refresh.finish { throw SQLException("offline") }
            awaitIdle()
            assertEquals(before, source.introspectionScope)

            fun classify(): ReferenceScope {
                val offset = myFixture.file.text.indexOf("web")
                val reference = checkNotNull(PsiTreeUtil.getNonStrictParentOfType(myFixture.file.findElementAt(offset), SqlReferenceExpression::class.java))
                return OutOfScopeClassifier(service).classify(reference)
            }
            fun unresolved() = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
                .filter { it.inspectionToolId == "SqlResolveInspection" }
                .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }

            // A restart loses the engine's failed state: only the saved scope and model remain.
            assertInstanceOf(classify(), ReferenceScope.OutOfScope::class.java)
            useService(Profile(outOfScopeReferences = true))
            assertInstanceOf(classify(), ReferenceScope.OutOfScope::class.java)
            assertEmpty(unresolved())
            // Control: the old persisted widening caused a fresh service to expose the false report.
            source.introspectionScope = widened
            useService(Profile(outOfScopeReferences = true))
            // A fresh file also avoids reusing the previous highlighting pass's filtered reports.
            myFixture.configureByText("restart-${UUID.randomUUID()}.sql", "SELECT * FROM hive.web.orders")
            setSqlDialectMapping(project, myFixture.file.virtualFile, PgDialect.INSTANCE, testRootDisposable)
            RecordingConsole(project, myFixture.file.virtualFile, source).use {
                assertEquals(ReferenceScope.Nonexistent, classify())
                val reports = unresolved()
                assertTrue(reports.toString(), "web" in reports)
            }
        }
    }
}
