package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.Dbms
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.lower
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.verify.VerifyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The same two products coexist, with their real DBMS ids, services and editor registrations. */
class DatafusionConsumerTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        for (language in DualDialectConsumerFixture.languages) {
            (language.editorProfile as DatafusionTestEditorProfile).apply {
                version = null
                parserSettings = emptyMap()
                fake.facts = RelationFacts()
                fake.revision++
                fake.pending = false
            }
        }
        DualDialectConsumerFixture.installEditors(project, testRootDisposable)
    }

    fun testOneConsumerKeepsBothLanguagesAndCatalogsIndependent() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val df = DfCatalogService(scope)
            val sp = SpCatalogService(scope)
            assertNotSame(df.profile, sp.profile)
            assertFalse(df.profile.persistenceId == sp.profile.persistenceId)
            for ((language, service) in listOf(DatafusionFixtureDialect to df, SpiceFixtureDialect to sp)) {
                val catalog = DualDialectConsumerFixture.catalog(language)
                assertSame(catalog, service.profile)
                assertTrue(catalog.dataSources.accepts(language.dbms))
                assertFalse(catalog.dataSources.accepts(Dbms.POSTGRES))
                val other = if (language === SpiceFixtureDialect) DatafusionFixtureDialect else SpiceFixtureDialect
                assertFalse(catalog.dataSources.accepts(other.dbms))
                myFixture.configureFromExistingVirtualFile(LightVirtualFile("${catalog.product}.sql", language,
                    "SELECT regexp_like('ABC', 'abc', 'i'); RESET datafusion.execution.batch_size"))
                assertSame(language, myFixture.file.language)
                assertNotNull(LanguageParserDefinitions.INSTANCE.forLanguage(language))
                assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
                val connected = ConnectedDatafusionEditorProfile(language, { service })
                assertEquals("bundled fallback", connected.catalogProvenance(myFixture.file))
                assertEquals(catalog.metadata.default.engineVersion, connected.snapshot(myFixture.file).engineVersion)
                val names = connected.functions(myFixture.file).map { it.name }
                assertTrue("regexp_like" in names)
                assertEquals(language === SpiceFixtureDialect, "ai_decide" in names)
                assertNull(connected.serverVersion(myFixture.file))
            }
        } finally { scope.cancel() }
    }

    fun testBothProductsDefaultToNativeModeAndRefuseUnqualifiedLowering() {
        for (language in DualDialectConsumerFixture.languages) {
            val pipe = DualDialectConsumerFixture.pipe(language)
            assertFalse(pipe.isProjectEnabled(project))
            assertTrue(pipe.validator.verify("SELECT ai_decide('x','{}')", null) is VerifyResult.Unavailable)
            assertTrue(pipe.lower("FROM sample |> DROP id") is PipeTranslation.Refused)
            val diagnostics = DualDialectConsumerFixture.diagnostics(language)
            assertEquals(language.profile.coreDialect, diagnostics.dialect)
            assertTrue(diagnostics.localParser)
            assertTrue(diagnostics.reportsPsiErrors)
        }
    }

    fun testObserversUseTheirOwnServiceAndResolver() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val df = DfObserver(project, scope)
        val sp = SpObserver(project, scope)
        try {
            assertEquals(DfCatalogService::class.java, df.profile.liveCatalog)
            assertEquals(SpCatalogService::class.java, sp.profile.liveCatalog)
            assertSame(DualDialectConsumerFixture.datafusion.dataSources, df.profile.dataSources)
            assertSame(DualDialectConsumerFixture.spice.dataSources, sp.profile.dataSources)
            df.start()
            sp.start()
            // Starting twice must not install another subscription.
            df.start()
            sp.start()
            for (type in listOf(DfHarvest::class.java, SpHarvest::class.java, DfCleanup::class.java, SpCleanup::class.java,
                DfRefresh::class.java, SpRefresh::class.java, DfObserverStartup::class.java, SpObserverStartup::class.java))
                assertNotNull(type.getConstructor().newInstance())
        } finally {
            Disposer.dispose(df)
            Disposer.dispose(sp)
            scope.cancel()
        }
    }
}
