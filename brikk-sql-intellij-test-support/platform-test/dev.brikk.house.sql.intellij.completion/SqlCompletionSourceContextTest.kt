package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.PsiComment
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.testFramework.LightVirtualFile
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureDialect
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureHighlighterFactory
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureParserDefinition
import dev.brikk.house.sql.intellij.fixture.FixtureDialect
import dev.brikk.house.sql.intellij.fixture.FixtureHighlighterFactory
import dev.brikk.house.sql.intellij.fixture.FixtureParserDefinition
import dev.brikk.house.sql.intellij.fixture.FixturePipeDialect
import java.util.UUID

/** Completion context comes from the source text's core tokens, not from PSI the grammar may not have built. */
class SqlCompletionSourceContextTest : CompletionTestCase() {
    private class Family(val profile: TestCompletionProfile, val contributor: Class<out CompletionContributor>, val function: String)

    private val families = listOf(
        Family(duckdbProfile, DuckdbCompletion::class.java, "abs"),
        Family(trinoProfile, TrinoCompletion::class.java, "abs"),
        Family(dorisProfile, DorisCompletion::class.java, "abs"),
        Family(clickhouseProfile, ClickhouseCompletion::class.java, "toDate"),
    )

    override fun setUp() {
        super.setUp()
        FixturePipeDialect.enabled = true
        FixturePipeDialect.claimFromFirst = true
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(FixtureDialect, FixtureParserDefinition(), testRootDisposable)
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(CHouseFixtureDialect, CHouseFixtureParserDefinition(), testRootDisposable)
        SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(FixtureDialect, FixtureHighlighterFactory(), testRootDisposable)
        SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(CHouseFixtureDialect, CHouseFixtureHighlighterFactory(), testRootDisposable)
    }

    private fun Family.offered(text: String, language: SqlLanguageDialect? = null): List<String> {
        val prefix = function.take(3)
        val source = text.replace("<name>", "$prefix<caret>")
        if (language == null) {
            configure(source, contributor)
        } else {
            myFixture.configureFromExistingVirtualFile(LightVirtualFile("context-${UUID.randomUUID()}.sql", language, source.replace("<caret>", "")))
            myFixture.editor.caretModel.moveToOffset(source.indexOf("<caret>"))
            register(contributor, LoadingOrder.FIRST)
        }
        return functionItems().map { it.name }
    }

    fun testCodePositionsOfferFunctions() {
        for (family in families) assertContainsElements(family.profile.metadata.dialect, family.offered("SELECT <name> FROM t"), family.function)
    }

    fun testStringsAndCommentsOfferNoFunctions() {
        for (family in families) for (text in listOf("SELECT '<name>' FROM t", "SELECT 1 -- <name>", "SELECT /* <name> */ 1", "SELECT 1;\n-- <name>")) {
            assertEmpty("${family.profile.metadata.dialect}: $text", family.offered(text))
        }
    }

    /** In PIPE text the grammar builds no reference, so only tokens see the qualifier. */
    fun testAQualifierInPipeTextIsSeen() {
        for (family in families) {
            family.profile.functionGates = SqlFunctionGates(qualifier = QualifierRule.Unqualified)
            assertEmpty(family.profile.metadata.dialect, family.offered("FROM events |> WHERE t.<name>"))
            assertContainsElements(family.profile.metadata.dialect, family.offered("FROM events |> WHERE <name>"), family.function)
        }
    }

    fun testAQualifierInALenientStatementIsSeen() {
        var asked: String? = null
        trinoProfile.functionGates = SqlFunctionGates(qualifier = QualifierRule.Namespaced { qualifier, _ -> asked = qualifier; emptyList() })
        val trino = families[1]
        assertEmpty(trino.offered("FROM events |> WHERE db.\"t\".<name>", FixtureDialect))
        assertEquals("db.\"t\"", asked)
    }

    /** A span the dialect's lexer masks is a comment in PSI but code in the source: it completes. */
    fun testAMaskedSpanCompletesAsCode() {
        val duckdb = families[0]
        assertContainsElements(duckdb.offered("SELECT * EXCLUDE (<name>) FROM t", FixtureDialect), "abs")
        assertInstanceOf(myFixture.file.findElementAt(myFixture.caretOffset - 1), PsiComment::class.java)
    }

    fun testClickhouseInlineDataIsNoCode() {
        val clickhouse = families[3]
        assertEmpty(clickhouse.offered("INSERT INTO t FORMAT TSV\na\t<name>", CHouseFixtureDialect))
    }

    fun testClickhousePartialTryCastBeforeExistingArgumentsCompletes() {
        val source = "SELECT try_ca<caret>('256' AS UInt8)"
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("try-cast-${UUID.randomUUID()}.sql", CHouseFixtureDialect, source.replace("<caret>", "")))
        myFixture.editor.caretModel.moveToOffset(source.indexOf("<caret>"))
        clickhouseProfile.functionGates = SqlFunctionGates(qualifier = QualifierRule.Unqualified, digitPrefix = true, autopopup = ExpressionPositions.STANDARD)
        register(ClickhouseCompletion::class.java, LoadingOrder.FIRST)
        accept(clickhouseProfile.metadata.default.completableFunctions().single { it.name == "TRY_CAST" })
        assertEquals("SELECT TRY_CAST(<caret>'256' AS UInt8)", resultWithCaret())
    }
}
