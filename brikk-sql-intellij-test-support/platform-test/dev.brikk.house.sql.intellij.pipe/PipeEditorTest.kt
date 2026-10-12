package dev.brikk.house.sql.intellij.pipe

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.LanguageAnnotators
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.pipeStageKeywords
import java.util.UUID

private val trino = testPipeDialects.first()

class TestStageCompletion : PipeStageCompletionContributor(trino)
class TestOpenStageCompletion : PipeStageCompletionContributor(trino) {
    override val stopsAtStageHead = false
    override fun stagePhrase(phrase: String): LookupElement = LookupElementBuilder.create(phrase).withTypeText("custom stage")
}
class TestEmbeddedStageCompletion : PipeStageCompletionContributor(object : PipeDialect by trino {
    override fun enablesEditing(file: PsiFile) = true
})
class TestOtherCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        result.addElement(LookupElementBuilder.create("other_item"))
    }
}
class TestStageKeywords : PipeStageKeywordAnnotator(trino)

class PipeEditorTest : BasePlatformTestCase() {
    private fun configure(text: String, completion: Class<out PipeStageCompletionContributor> = TestStageCompletion::class.java) {
        myFixture.configureByText("pipe-${UUID.randomUUID()}.sql", text)
        val language = myFixture.file.language.id
        val plugin = DefaultPluginDescriptor(PluginId.getId("brikk.test"), javaClass.classLoader)
        val point = CompletionContributor.EP.point
        point.registerExtension(CompletionContributorEP(language, completion.name, plugin), LoadingOrder.FIRST, testRootDisposable)
        point.registerExtension(CompletionContributorEP(language, TestOtherCompletion::class.java.name, plugin), LoadingOrder.LAST, testRootDisposable)
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

    fun testPhrasesMatchAcrossAnyWhitespace() {
        val phrase = pipeStageKeywords("trino").first { it.count { c -> c == ' ' } == 2 }
        val words = phrase.split(' ')
        for (gap in listOf("  ", "\n   ")) {
            configure("FROM t |> ${words[0]}$gap${words[1]}$gap${words[2].take(1)}<caret>")
            myFixture.completeBasic()
            assertEquals("FROM t |> $phrase", myFixture.editor.document.text)
        }
    }

    fun testStagePhrasesInANestedPipeline() {
        configure("SELECT * FROM (FROM t |> <caret>) AS s")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), pipeStageKeywords("trino"))
    }

    fun testANestedPipelineCompletesItsOwnInputColumns() {
        configure("FROM t |> SELECT a AS outer_col |> WHERE x IN (FROM u |> SELECT b AS inner_col |> WHERE <caret>)")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertContainsElements(items, "inner_col")
        assertDoesntContain(items, "outer_col")
    }

    fun testAQueryNestedInAPipelineGetsNoOuterColumns() {
        configure("FROM t |> SELECT a AS outer_col |> WHERE x IN (SELECT <caret>)")
        myFixture.completeBasic()
        assertDoesntContain(myFixture.lookupElementStrings.orEmpty(), "outer_col")
    }

    fun testStageHeadsHideOtherItemsByDefault() {
        configure("FROM t |> <caret>")
        myFixture.completeBasic()
        assertDoesntContain(myFixture.lookupElementStrings.orEmpty(), "other_item")
    }

    fun testADialectCanKeepOtherItemsAndPresentPhrasesItsWay() {
        configure("FROM t |> <caret>", TestOpenStageCompletion::class.java)
        val items = myFixture.completeBasic().orEmpty()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "other_item", "WHERE")
        val where = items.single { it.lookupString == "WHERE" }
        assertEquals("custom stage", LookupElementPresentation.renderElement(where).typeText)
    }

    fun testOnlyBasicCompletionOffersStages() {
        configure("FROM t |> <caret>")
        myFixture.complete(CompletionType.SMART)
        assertDoesntContain(myFixture.lookupElementStrings.orEmpty(), "WHERE")
    }

    fun testFilesEnabledByTheProfileGetNoInputColumnsByDefault() {
        configure("FROM t |> SELECT a |> WHERE <caret>", TestEmbeddedStageCompletion::class.java)
        myFixture.completeBasic()
        assertDoesntContain(myFixture.lookupElementStrings.orEmpty(), "a")
    }

    fun testTypeableInputColumnsAreOfferedInAStage() {
        configure("FROM t |> SELECT a, b AS c, a + 1 |> WHERE <caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertContainsElements(items, "a", "c")
        assertDoesntContain(items, "b")
        assertTrue(items.toString(), items.none { it.startsWith("_col_") })
    }

    private fun coloredStagePhrases() = myFixture.doHighlighting()
        .filter { it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.KEYWORD }
        .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }

    fun testStagePhrasesAreColoredAfterEveryPipe() {
        configure("FROM t |> WHERE a IN (FROM u |> LIMIT 1) |> ORDER BY a")
        assertSameElements(coloredStagePhrases(), "WHERE", "LIMIT", "ORDER BY")
    }

    fun testStagesBeforeIncompleteInputStayColored() {
        configure("FROM t |> EXTEND 1 AS x |> SELECT 'unfinished")
        assertSameElements(coloredStagePhrases(), "EXTEND", "SELECT")
        configure("FROM t |> WHERE a IN (1, 2 |> ORDER BY a")
        assertSameElements(coloredStagePhrases(), "WHERE", "ORDER BY")
    }

    fun testStagesCompleteWhileLaterInputIsIncomplete() {
        configure("FROM t |> EXTEND 1 AS x |> <caret> |> SELECT 'unfinished")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), pipeStageKeywords("trino"))
    }

    fun testNothingCompletesInsideAnUnterminatedString() {
        configure("FROM t |> SELECT input_col |> WHERE x = 'in <caret>")
        myFixture.completeBasic()
        assertDoesntContain(myFixture.lookupElementStrings.orEmpty(), "input_col")
    }

    fun testPreviewIsOfferedWhereTheProfileEnablesIt() {
        myFixture.configureByText("pipe-${UUID.randomUUID()}.sql", "FROM t |> WHERE a = 1")
        val withoutPreview = object : PipeDialect by trino {
            override fun enablesPreview(file: PsiFile) = false
        }
        for ((dialect, offered) in listOf(trino to true, withoutPreview to false)) {
            assertEquals(offered, object : PipePreviewIntention(dialect) {}.isAvailable(project, myFixture.editor, myFixture.file))
            val event = TestActionEvent.createTestEvent(SimpleDataContext.builder()
                .add(CommonDataKeys.PSI_FILE, myFixture.file).add(CommonDataKeys.EDITOR, myFixture.editor).build())
            object : PipePreviewAction(dialect) {}.update(event)
            assertEquals(offered, event.presentation.isEnabledAndVisible)
        }
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
