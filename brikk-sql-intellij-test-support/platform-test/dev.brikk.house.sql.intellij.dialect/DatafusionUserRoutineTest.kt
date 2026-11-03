package dev.brikk.house.sql.intellij.dialect

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlResolveResult
import com.intellij.testFramework.LightVirtualFile
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectTestCase
import dev.brikk.house.sql.intellij.parser.DatafusionFamilyParserDefinition
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import java.util.UUID

/** A real cached IDE catalog, not a server connection or a claim of PostgreSQL wire compatibility. */
class DatafusionUserRoutineTest : AutoIntrospectTestCase() {
    fun testConnectionOwnedRoutineSurvivesNativePrototypeFiltering() {
        orders.parent!!.routines.createOrGet("pg_sleep")
        val settings = CodeInsightSettings.getInstance()
        val old = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        Disposer.register(testRootDisposable) { settings.AUTOCOMPLETE_ON_CODE_COMPLETION = old }
        val dialect = SpiceFixtureDialect
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(dialect, DatafusionFamilyParserDefinition(dialect), testRootDisposable)
        SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(dialect, object : SqlSyntaxHighlighterFactory.Base(dialect) {}, testRootDisposable)
        val descriptor = DefaultPluginDescriptor(PluginId.getId("brikk.datafusion.udf.test"), javaClass.classLoader)
        for (type in listOf(SpFilter::class.java, SpFunctions::class.java))
            CompletionContributor.EP.point.registerExtension(CompletionContributorEP(dialect.id, type.name, descriptor), LoadingOrder.FIRST, testRootDisposable)
        val text = "SELECT pg_sl"
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("routine-${UUID.randomUUID()}.sql", dialect, text))
        myFixture.editor.caretModel.moveToOffset(text.length)
        RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            setSqlDialectMapping(project, myFixture.file.virtualFile, dialect, testRootDisposable)
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("loaded", ObjectKind.DATABASE).append("web", ObjectKind.SCHEMA)))
            val items = myFixture.completeBasic().orEmpty()
            assertTrue(items.map { it.lookupString }.toString(), items.any {
                (it.`object` as? SqlResolveResult)?.targetSymbol?.dasObject?.let { obj -> obj.name == "pg_sleep" && obj.dasParent?.name == "web" } == true
            })
            assertTrue(items.none { it.lookupString == "pg_sleep" && it.`object` is BuiltinFunction.Prototype })
        }
    }
}
