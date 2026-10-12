package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.Language
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiFile
import com.intellij.sql.psi.SqlLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.TRINO_METADATA
import java.util.UUID

/** A profile over real metadata whose settings each test chooses. */
class TestCompletionProfile(override val metadata: DialectMetadata) : SqlCompletionProfile {
    override val language: Language = SqlLanguage.INSTANCE
    override var styles = SqlLookupStyles.DEFAULT
    override var functionGates = SqlFunctionGates.OFF
    var serverOnly: List<FunctionPresentation> = emptyList()

    override fun serverOnlyFunctions(file: PsiFile) = serverOnly

    fun reset() {
        styles = SqlLookupStyles.DEFAULT
        functionGates = SqlFunctionGates.OFF
        serverOnly = emptyList()
    }
}

val duckdbProfile = TestCompletionProfile(DUCKDB_METADATA)
val dorisProfile = TestCompletionProfile(DORIS_METADATA)
val trinoProfile = TestCompletionProfile(TRINO_METADATA)

class DuckdbCompletion : SqlCompletionContributor(duckdbProfile)
class DorisCompletion : SqlCompletionContributor(dorisProfile)
class TrinoCompletion : SqlCompletionContributor(trinoProfile)

/** Completion fixture with the chosen contributor registered first for the file's language; lookups never auto-insert. */
abstract class CompletionTestCase : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        listOf(duckdbProfile, dorisProfile, trinoProfile).forEach(TestCompletionProfile::reset)
        val settings = CodeInsightSettings.getInstance()
        val autocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        com.intellij.openapi.util.Disposer.register(testRootDisposable) { settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete }
    }

    protected fun configure(text: String, contributor: Class<out CompletionContributor>) {
        myFixture.configureByText("completion-${UUID.randomUUID()}.sql", text)
        register(contributor, LoadingOrder.FIRST)
    }

    protected fun register(contributor: Class<out CompletionContributor>, order: LoadingOrder) {
        val plugin = DefaultPluginDescriptor(PluginId.getId("brikk.test"), javaClass.classLoader)
        CompletionContributor.EP.point.registerExtension(
            CompletionContributorEP(myFixture.file.language.id, contributor.name, plugin), order, testRootDisposable,
        )
    }

    protected fun complete(invocationCount: Int = 1): List<LookupElement> =
        myFixture.complete(CompletionType.BASIC, invocationCount).orEmpty().toList()

    protected fun functionItems(invocationCount: Int = 1): List<FunctionPresentation> =
        complete(invocationCount).mapNotNull { it.`object` as? FunctionPresentation }

    /** Completes and accepts the item whose lookup object is [identity]. */
    protected fun accept(identity: Any) {
        val item = complete().singleOrNull { it.`object` == identity } ?: error("$identity not offered")
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    }

    protected fun resultWithCaret(): String {
        val document = myFixture.editor.document
        val caret = myFixture.editor.caretModel.offset
        return document.text.substring(0, caret) + "<caret>" + document.text.substring(caret)
    }
}
