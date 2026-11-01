package dev.brikk.house.sql.intellij.dialect

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.ExternalLanguageAnnotators
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.impl.SqlResolveExtension
import dev.brikk.house.sql.intellij.catalog.*
import dev.brikk.house.sql.intellij.diagnostics.DatafusionNativeErrorProfile
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorPsiFilter
import dev.brikk.house.sql.intellij.docs.DatafusionDocumentationProvider
import dev.brikk.house.sql.intellij.parser.DatafusionFamilyParserDefinition
import dev.brikk.house.sql.intellij.pipe.DatafusionPipeDialect
import kotlinx.coroutines.CoroutineScope

/** One consumer classloader, two independent products. Test-only, never packaged in a library jar. */
object DualDialectConsumerFixture {
    val datafusion = DatafusionCatalogProfile(DatafusionFixtureDialect.dbms)
    val spice = SpiceCatalogProfile(SpiceFixtureDialect.dbms)
    val languages = listOf(DatafusionFixtureDialect, SpiceFixtureDialect)

    fun catalog(language: DatafusionFamilyDialect) = when (language) {
        DatafusionFixtureDialect -> datafusion
        SpiceFixtureDialect -> spice
        else -> error("Not a consumer language: ${language.id}")
    }

    fun pipe(language: DatafusionFamilyDialect, enabled: (Project) -> Boolean = { false }) =
        DatafusionPipeDialect(language, catalog(language), enabled)

    private val diagnosticProfiles by lazy { languages.associateWith { DatafusionNativeErrorProfile(it, catalog(it), pipe(it)) } }
    fun diagnostics(language: DatafusionFamilyDialect) = diagnosticProfiles.getValue(language)

    /** Mirrors one plugin's language-scoped extension registrations, with disposable test ownership. */
    fun installEditors(project: Project, disposable: Disposable) {
        val descriptor = DefaultPluginDescriptor(PluginId.getId("brikk.datafusion.spice.fixture"), javaClass.classLoader)
        for (language in languages) {
            val profile = language.editorProfile
            LanguageParserDefinitions.INSTANCE.addExplicitExtension(language, DatafusionFamilyParserDefinition(language), disposable)
            SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(language, object : SqlSyntaxHighlighterFactory.Base(language) {}, disposable)
            LanguageDocumentation.INSTANCE.addExplicitExtension(language, DatafusionDocumentationProvider(profile), disposable)
            val native = diagnostics(language)
            ExternalLanguageAnnotators.INSTANCE.addExplicitExtension(language, object : NativeErrorAnnotator(native) {}, disposable)
            HighlightErrorFilter.EP_NAME.getPoint(project).registerExtension(object : NativeErrorPsiFilter(native) {}, disposable)
            val classes = if (language === SpiceFixtureDialect)
                listOf(SpArguments::class.java, SpFilter::class.java, SpColumns::class.java, SpFunctions::class.java)
            else listOf(DfArguments::class.java, DfFilter::class.java, DfColumns::class.java, DfFunctions::class.java)
            for (type in classes) CompletionContributor.EP.point.registerExtension(
                CompletionContributorEP(language.id, type.name, descriptor), LoadingOrder.FIRST, disposable)
        }
        SqlResolveExtension.EP_NAME.point.registerExtension(PgFamilyResolveExtension(), disposable)
        SqlResolveExtension.EP_NAME.point.registerExtension(DfResolve(), disposable)
        SqlResolveExtension.EP_NAME.point.registerExtension(SpResolve(), disposable)
        GotoDeclarationHandler.EP_NAME.point.registerExtension(DfGoto(), disposable)
        GotoDeclarationHandler.EP_NAME.point.registerExtension(SpGoto(), disposable)
        for (suppressor in listOf(DfRelationSuppressor(), SpRelationSuppressor()))
            SqlInspectionSuppressorDelegate.EP_NAME.point.registerExtension(suppressor, disposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(DatafusionFixtureDialect, DfRelations(), disposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(SpiceFixtureDialect, SpRelations(), disposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(DatafusionFixtureDialect, DfFunctionsCheck(), disposable)
        LanguageAnnotators.INSTANCE.addExplicitExtension(SpiceFixtureDialect, SpFunctionsCheck(), disposable)
    }
}

// These compile the consumer service constructors and lifecycle wiring against the actual support API.
class DfCatalogService(scope: CoroutineScope) : LiveCatalogService(scope) {
    override val profile = DualDialectConsumerFixture.datafusion
    override fun persistenceDirectory() = null // Keep fixtures out of the user's disk cache.
}
class SpCatalogService(scope: CoroutineScope) : LiveCatalogService(scope) {
    override val profile = DualDialectConsumerFixture.spice
    override fun persistenceDirectory() = null
}
class DfHarvest : HarvestConnectionInterceptor(DfCatalogService::class.java)
class SpHarvest : HarvestConnectionInterceptor(SpCatalogService::class.java)
class DfCleanup : LiveCatalogCleanup(DfCatalogService::class.java)
class SpCleanup : LiveCatalogCleanup(SpCatalogService::class.java)
class DfRefresh : RefreshLiveCatalogAction(DfCatalogService::class.java)
class SpRefresh : RefreshLiveCatalogAction(SpCatalogService::class.java)

private fun observerProfile(catalog: DatafusionCatalogProfile, service: Class<out LiveCatalogService>) = object : ExecutionObserverProfile {
    override val dbms = catalog.dbms
    override val dataSources = catalog.dataSources
    override val statements = catalog.statementDetector
    override val liveCatalog = service
}
class DfObserver(project: Project, scope: CoroutineScope) : ExecutionObserver(project, scope) {
    override val profile = observerProfile(DualDialectConsumerFixture.datafusion, DfCatalogService::class.java)
}
class SpObserver(project: Project, scope: CoroutineScope) : ExecutionObserver(project, scope) {
    override val profile = observerProfile(DualDialectConsumerFixture.spice, SpCatalogService::class.java)
}
class DfObserverStartup : ExecutionObserverStartup(DfObserver::class.java)
class SpObserverStartup : ExecutionObserverStartup(SpObserver::class.java)
