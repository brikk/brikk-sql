package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Disposer
import com.intellij.sql.psi.SqlResolveResult
import dev.brikk.house.sql.intellij.completion.TrinoCompletion
import dev.brikk.house.sql.intellij.completion.configureDialect
import dev.brikk.house.sql.intellij.completion.trinoProfile
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.metadata.FunctionPresentation

/** A data source's routine named like a metadata function is the user's: completion keeps it beside the metadata item. */
class DataSourceRoutineCompletionTest : AutoIntrospectTestCase() {
    private fun List<LookupElement>.dataSourceAbs() = filter { item ->
        (item.`object` as? SqlResolveResult)?.targetSymbol?.dasObject?.let { it.name == "abs" && it.dasParent?.name == "web" } == true
    }

    fun testADataSourceRoutineStaysBesideTheMetadataFunction() {
        checkNotNull(orders.parent).routines.createOrGet("abs")
        trinoProfile.reset()
        val settings = CodeInsightSettings.getInstance()
        val autocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        Disposer.register(testRootDisposable) { settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete }
        myFixture.configureDialect("PostgreSQL", "SELECT ab<caret>")
        RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("loaded", ObjectKind.DATABASE).append("web", ObjectKind.SCHEMA)))
            assertNotEmpty(myFixture.complete(CompletionType.BASIC, 1).orEmpty().toList().dataSourceAbs())
            LookupManager.getInstance(project).hideActiveLookup()
            val plugin = DefaultPluginDescriptor(PluginId.getId("brikk.test"), javaClass.classLoader)
            CompletionContributor.EP.point.registerExtension(
                CompletionContributorEP(myFixture.file.language.id, TrinoCompletion::class.java.name, plugin), LoadingOrder.FIRST, testRootDisposable,
            )
            val items = myFixture.complete(CompletionType.BASIC, 1).orEmpty().toList()
            assertNotEmpty(items.dataSourceAbs())
            assertTrue(items.any { (it.`object` as? FunctionPresentation)?.name == "abs" })
        }
    }
}
