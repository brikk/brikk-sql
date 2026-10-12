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
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.psi.SqlLanguage
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.KeywordCatalog
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.metadata.TRINO_METADATA
import java.util.UUID

/**
 * Opens [text] in a file of the SQL dialect [dialect]. The file carries its language itself:
 * 263 no longer applies dialect mappings to light test files.
 */
fun CodeInsightTestFixture.configureDialect(dialect: String, text: String) {
    val language = Language.findLanguageByID(dialect) as SqlLanguageDialect
    configureFromExistingVirtualFile(LightVirtualFile("dialect-${UUID.randomUUID()}.sql", language, text.replace("<caret>", "")))
    editor.caretModel.moveToOffset(text.indexOf("<caret>"))
    check(file.language == language) { "${file.language} is not $dialect" }
}

/** A profile over real metadata whose settings each test chooses. */
class TestCompletionProfile(override val metadata: DialectMetadata) : SqlCompletionProfile {
    override val language: Language = SqlLanguage.INSTANCE
    override var styles = SqlLookupStyles.DEFAULT
    override var nativeKinds = emptyMap<String, SqlLookupKind>()
    override var functionGates = SqlFunctionGates.OFF
    override var tablePositions = TablePositions.STANDARD
    override var keywords = KeywordScope.NONE
    override var propertyQuote = '\''
    var serverOnly: List<FunctionPresentation> = emptyList()
    var builtinFilter: ((String) -> Boolean)? = null
    var keywordCatalog: KeywordCatalog? = null

    /** Calls of each per-file hook since the last [reset]. */
    val calls = mutableMapOf<String, Int>()

    private fun <T> counted(hook: String, value: () -> T): T = value().also { calls.merge(hook, 1, Int::plus) }

    override fun snapshot(file: PsiFile) = counted("snapshot") { super.snapshot(file) }
    override fun functions(file: PsiFile) = counted("functions") { metadata.default.completableFunctions() }
    override fun serverOnlyFunctions(file: PsiFile) = counted("serverOnlyFunctions") { serverOnly }
    override fun keywordCatalog(file: PsiFile, snapshot: MetadataSnapshot) =
        counted("keywordCatalog") { keywordCatalog ?: super.keywordCatalog(file, snapshot) }
    override fun builtinFunctionFilter(file: PsiFile) = builtinFilter

    fun reset() {
        styles = SqlLookupStyles.DEFAULT
        nativeKinds = emptyMap()
        functionGates = SqlFunctionGates.OFF
        tablePositions = TablePositions.STANDARD
        keywords = KeywordScope.NONE
        propertyQuote = '\''
        serverOnly = emptyList()
        builtinFilter = null
        keywordCatalog = null
        calls.clear()
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
        Disposer.register(testRootDisposable) { settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete }
    }

    protected fun configure(text: String, contributor: Class<out CompletionContributor>) {
        myFixture.configureByText("completion-${UUID.randomUUID()}.sql", text)
        register(contributor, LoadingOrder.FIRST)
    }

    protected fun register(contributor: Class<out CompletionContributor>, order: LoadingOrder, language: String = myFixture.file.language.id) {
        val plugin = DefaultPluginDescriptor(PluginId.getId("brikk.test"), javaClass.classLoader)
        CompletionContributor.EP.point.registerExtension(
            CompletionContributorEP(language, contributor.name, plugin), order, testRootDisposable,
        )
    }

    protected fun complete(invocationCount: Int = 1): List<LookupElement> =
        myFixture.complete(CompletionType.BASIC, invocationCount).orEmpty().toList()

    protected fun functionItems(invocationCount: Int = 1): List<FunctionPresentation> =
        complete(invocationCount).mapNotNull { it.`object` as? FunctionPresentation }

    /** Completes and accepts, with [char], the item whose lookup object is [identity]. */
    protected fun accept(identity: Any, char: Char = Lookup.NORMAL_SELECT_CHAR) {
        val item = complete().singleOrNull { it.`object` == identity } ?: error("$identity not offered")
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(char)
    }

    protected fun resultWithCaret(): String {
        val document = myFixture.editor.document
        val caret = myFixture.editor.caretModel.offset
        return document.text.substring(0, caret) + "<caret>" + document.text.substring(caret)
    }
}
