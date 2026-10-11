package dev.brikk.house.sql.intellij.pipe

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.lang.LanguageAnnotators
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.pipeStageKeywords
import java.util.UUID

private val trino = testPipeDialects.first()

class TestStageCompletion : PipeStageCompletionContributor(trino)
class TestStageKeywords : PipeStageKeywordAnnotator(trino)

class PipeEditorTest : BasePlatformTestCase() {
    private fun configure(text: String) {
        myFixture.configureByText("pipe-${UUID.randomUUID()}.sql", text)
        val language = myFixture.file.language.id
        val plugin = DefaultPluginDescriptor(PluginId.getId("brikk.test"), javaClass.classLoader)
        CompletionContributor.EP.point.registerExtension(
            CompletionContributorEP(language, TestStageCompletion::class.java.name, plugin), testRootDisposable,
        )
        LanguageAnnotators.INSTANCE.addExplicitExtension(myFixture.file.language, TestStageKeywords(), testRootDisposable)
    }

    fun testEveryStagePhraseIsOfferedAfterAPipe() {
        configure("FROM t |> <caret>")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), pipeStageKeywords("trino"))
    }

    fun testAMultiWordPhraseIsInsertedOnce() {
        val phrases = pipeStageKeywords("trino")
        val phrase = phrases.first { candidate ->
            candidate.count { it == ' ' } == 2 && phrases.count { it.startsWith(candidate.substringBeforeLast(' ')) } == 1
        }
        val typed = phrase.substringBeforeLast(' ') + " " + phrase.substringAfterLast(' ').take(1).lowercase()
        configure("FROM t |> $typed<caret>")
        myFixture.completeBasic()
        assertEquals("FROM t |> $phrase", myFixture.editor.document.text)
    }

    fun testTypeableInputColumnsAreOfferedInAStage() {
        configure("FROM t |> SELECT a, b AS c, a + 1 |> WHERE <caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertContainsElements(items, "a", "c")
        assertDoesntContain(items, "b")
        assertTrue(items.toString(), items.none { it.startsWith("_col_") })
    }

    fun testStagePhrasesAreColoredAfterEveryPipe() {
        configure("FROM t |> WHERE a IN (FROM u |> LIMIT 1) |> ORDER BY a")
        val colored = myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.KEYWORD }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }
        assertSameElements(colored, "WHERE", "LIMIT", "ORDER BY")
    }

    fun testErrorRangesStayInsideTheStatement() {
        val statement = "  FROM t\n|> FROBNICATE x"
        val text = "SELECT 1;\n$statement"
        myFixture.configureByText("pipe-${UUID.randomUUID()}.sql", text)
        val source = trino.pipeText(myFixture.file)
        val start = text.indexOf(statement)
        val refused = trino.lower(statement) as PipeTranslation.Refused
        assertEquals("FROBNICATE", source.errorRange(start, statement, refused.range)?.substring(text))
        assertEquals("F", source.errorRange(start, statement, null)?.substring(text))
        assertEquals("x", source.errorRange(start, statement, SyntacticRange(statement.length, statement.length))?.substring(text))
        assertEquals(TextRange(1, 3), source.errorRange(0, "x\uD83D\uDE00", SyntacticRange(3, 3)))
    }
}
