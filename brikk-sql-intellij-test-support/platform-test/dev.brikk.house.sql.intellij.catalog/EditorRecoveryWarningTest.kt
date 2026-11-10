package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.psi.DbPsiFacade
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import java.util.concurrent.CancellationException

class EditorRecoveryWarningTest : BasePlatformTestCase() {
    private class LookupFailure : IllegalStateException("lookup failed")
    private class OtherLookupFailure : IllegalArgumentException("another lookup failed")
    private class Refresh : RefreshLiveCatalogAction(LiveService::class.java)
    private lateinit var errors: BackgroundErrors

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
    }

    override fun tearDown() = RunAll(
        { errors.awaitScopes() },
        { errors.assertNone() },
        { super.tearDown() },
    ).run()

    fun testRecoveriesWarnOncePerSiteAndExceptionClassKeepFallbacksAndPropagateCancellation() {
        val original = myFixture.configureByText("recovery.sql", "SELECT 1")
        var failure: Throwable = LookupFailure()
        val file = object : PsiFile by original {
            override fun getProject(): Project = throw failure
        }
        val resolver = DialectDataSourceResolver(Dbms.POSTGRES)
        repeat(2) {
            assertRecoveries(listOf(
                "DialectDataSourceResolver.resolve" to { assertSame(DataSourceResolution.NotFound, resolver.resolve(file)) },
                "DialectDataSourceResolver.consoleNamespace" to { assertEquals(emptyList<String>(), resolver.consoleNamespace(file)) },
                "DialectDataSourceResolver.consoleSession" to { assertNull(resolver.consoleSession(file)) },
            )) { failure = it }
        }
    }

    fun testRefreshActionRecoveriesWarnOnceKeepTheActionUnavailableAndPropagateCancellation() {
        var failure: Throwable = LookupFailure()
        val profile = object : LiveCatalogProfile by LiveProfile(dbms = Dbms.POSTGRES) {
            override val dataSources: DialectDataSourceResolver get() = throw failure
        }
        val service = LiveService(errors.newScope(testRootDisposable), profile, null)
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(LiveService::class.java, service, testRootDisposable)
        val source = LocalDataSource().apply { name = "recovery"; isAutoSynchronize = false }
        withSource(source) {
            val popup = DataContext { key ->
                when (key) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.EDITOR.name -> throw failure
                    else -> null
                }
            }
            val search = DataContext { key -> if (key == CommonDataKeys.PROJECT.name) project else null }
            fun unavailable(place: String, context: DataContext) {
                val presentation = Presentation()
                Refresh().update(AnActionEvent.createFromDataContext(place, presentation, context))
                assertFalse(presentation.isEnabled)
                assertEquals(place != ActionPlaces.EDITOR_POPUP, presentation.isVisible)
            }
            repeat(2) {
                assertRecoveries(listOf(
                    "RefreshLiveCatalogAction.sqlAtCaret" to { unavailable(ActionPlaces.EDITOR_POPUP, popup) },
                    // Reading PSI_FILE is outside target's recovery. Fail its resolver initialization instead.
                    "RefreshLiveCatalogAction.target" to { unavailable(ActionPlaces.ACTION_SEARCH, search) },
                )) { failure = it }
            }
        }
    }

    fun testDasRecoveriesWarnOnceAfterSuccessfulResolutionKeepNullAndPropagateCancellation() {
        val file = myFixture.configureByText("das-recovery.sql", "SELECT 1")
        var failure: Throwable? = null
        val source = object : LocalDataSource() {
            override fun getDbms(): Dbms = Dbms.POSTGRES
            override fun getUniqueId(): String = failure?.let { throw it } ?: super.getUniqueId()
        }.apply { name = "DAS recovery"; isAutoSynchronize = false }
        withSource(source) {
            val resolver = DialectDataSourceResolver(Dbms.POSTGRES)
            val facts = DasRelationFacts(resolver)
            try {
                repeat(2) {
                    assertRecoveries(listOf(
                        "DasRelationFacts.facts" to {
                            assertSame("the identity fault is after resolution", source, resolver.resolve(file).dataSource)
                            assertNull(facts.facts(file))
                        },
                        "DasRelationFacts.navigationTarget" to {
                            assertSame("the identity fault is after resolution", source, resolver.resolve(file).dataSource)
                            assertNull(facts.navigationTarget(file, "missing"))
                        },
                    )) { failure = it }
                }
            } finally {
                failure = null
            }
        }
    }

    private fun withSource(source: LocalDataSource, operation: () -> Unit) {
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(source)
        try {
            operation()
        } finally {
            manager.removeDataSource(source)
            DbPsiFacade.getInstance(project).clearCaches()
        }
    }

    private fun assertRecoveries(sites: List<Pair<String, () -> Unit>>, failWith: (Throwable) -> Unit) {
        val messages = sites.map { "${it.first} failed" }
        val warnings = ArrayList<Pair<String, Throwable?>>()
        val logging = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                if (message in messages) {
                    assertEquals("#dev.brikk.house.sql.intellij.EditorRecoveryWarnings", category)
                    warnings += message to t
                }
                return super.processWarn(category, message, t)
            }
        })
        fun recover() = sites.forEach { it.second() }
        try {
            val lookupFailure = freshFailures(LookupFailure::class.java)
            val first = lookupFailure()
            failWith(first)
            recover()
            assertEquals(messages.map { it to first }, warnings)
            failWith(lookupFailure())
            recover()
            assertEquals("a new exception of the same class adds no WARN", sites.size, warnings.size)
            val other = freshFailures(OtherLookupFailure::class.java)()
            failWith(other)
            recover()
            assertEquals("each site reports a different exception class", messages.map { it to other }, warnings.drop(sites.size))
            for (cancel in listOf(ProcessCanceledException(), CancellationException())) {
                failWith(cancel)
                for ((_, operation) in sites) {
                    try {
                        operation()
                        fail("Cancellation must propagate")
                    } catch (actual: Throwable) {
                        assertSame(cancel, actual)
                    }
                }
            }
            assertEquals("cancellation adds no WARN", sites.size * 2, warnings.size)
        } finally {
            logging.finish()
        }
    }

    /** A fresh exception class per run, without clearing or weakening the production WARN-once state. */
    private fun freshFailures(template: Class<out Exception>): () -> Exception {
        val bytes = checkNotNull(template.classLoader.getResourceAsStream(template.name.replace('.', '/') + ".class")).use { it.readBytes() }
        val type = object : ClassLoader(template.classLoader) {
            fun define(): Class<*> = defineClass(template.name, bytes, 0, bytes.size)
        }.define()
        val constructor = type.getDeclaredConstructor().apply { isAccessible = true }
        return { constructor.newInstance() as Exception }
    }
}
