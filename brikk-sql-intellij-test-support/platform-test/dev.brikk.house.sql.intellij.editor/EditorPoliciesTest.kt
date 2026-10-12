package dev.brikk.house.sql.intellij.editor

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.database.sql.common.core.psi.impl.support.SqlNestedCommenter
import com.intellij.lang.LanguageCommenters
import com.intellij.lang.LanguageQuoteHandling
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.fixture.FixtureDialect
import dev.brikk.house.sql.intellij.fixture.FixtureTestCase

class FixtureErrorFilter : SubstrateErrorFilter(FixtureDialect)
class NewlineQuotes : SqlDialectQuoteHandler(EnterInString.Newline)
class SplitQuotes : SqlDialectQuoteHandler(EnterInString.Split)

class EditorPoliciesTest : FixtureTestCase() {
    private fun enter(text: String, quotes: SqlDialectQuoteHandler): String {
        LanguageQuoteHandling.INSTANCE.addExplicitExtension(FixtureDialect, quotes, testRootDisposable)
        configureFixture(text)
        myFixture.type('\n')
        return myFixture.editor.document.text
    }

    fun testEnterInAStringInsertsANewlineUnderTheNewlinePolicy() {
        assertEquals("SELECT 'ab\ncd' FROM t", enter("SELECT 'ab<caret>cd' FROM t", NewlineQuotes()))
    }

    fun testEnterInAStringSplitsItUnderTheSplitPolicy() {
        assertTrue(enter("SELECT 'ab<caret>cd' FROM t", SplitQuotes()).contains("'ab' ||"))
    }

    fun testEnterInAPrefixedStringSplitsItUnderTheSplitPolicy() {
        assertTrue(enter("SELECT E'ab<caret>cd' FROM t", SplitQuotes()).contains("'ab' ||"))
    }

    fun testEnterInACollapsedSpanNeverSplitsIt() {
        assertFalse(enter("SELECT {'k': 1<caret>2} FROM t", SplitQuotes()).contains("||"))
    }

    fun testSubstrateErrorsAreHiddenOnlyInTheDialect() {
        HighlightErrorFilter.EP_NAME.getPoint(project).registerExtension(FixtureErrorFilter(), testRootDisposable)
        configureFixture("SELECT * FROM WHERE (")
        assertNotNull(PsiTreeUtil.findChildOfType(myFixture.file, PsiErrorElement::class.java))
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
        val postgres = PsiFileFactory.getInstance(project).createFileFromText("pg.sql", PgDialect.INSTANCE, "SELECT * FROM WHERE (")
        val error = PsiTreeUtil.findChildOfType(postgres, PsiErrorElement::class.java)
        assertTrue(error != null && FixtureErrorFilter().shouldHighlightErrorElement(error))
    }

    fun testOverlayColorsListedIdentifiersAsKeywords() {
        configureFixture("SELECT buckets, other FROM t")
        val base = SyntaxHighlighterFactory.getSyntaxHighlighter(FixtureDialect, project, myFixture.file.virtualFile)
        val overlay = KeywordOverlayHighlighter(base, FixtureDialect, setOf("BUCKETS"))
        val lexer = overlay.highlightingLexer
        lexer.start(myFixture.editor.document.text)
        val types = buildMap<String, IElementType> {
            while (lexer.tokenType != null) {
                put(lexer.tokenText, lexer.tokenType!!)
                lexer.advance()
            }
        }
        assertEquals(base.getTokenHighlights(SqlTokens.SQL_KEYWORD_TOKEN).toList(), overlay.getTokenHighlights(types["buckets"]).toList())
        assertEquals(SqlTokens.SQL_IDENT, types["other"])
        val again = KeywordOverlayHighlighter(base, FixtureDialect, setOf("BUCKETS")).highlightingLexer
        again.start("buckets")
        assertSame(types["buckets"], again.tokenType)
    }

    fun testPlatformNestedCommenterServesTheDialect() {
        LanguageCommenters.INSTANCE.addExplicitExtension(FixtureDialect, SqlNestedCommenter(), testRootDisposable)
        configureFixture("SELECT<caret> 1 FROM t")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("-- SELECT 1 FROM t", myFixture.editor.document.text)
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("SELECT 1 FROM t", myFixture.editor.document.text)
    }

    fun testPgFamilyDialectKnowsThePostgresBuiltins() {
        val names = listOf("date_trunc", "now", "string_agg", "generate_series")
        assertEquals(names.map(PgDialect.INSTANCE.supportedFunctions::contains), names.map(FixtureDialect.supportedFunctions::contains))
        assertTrue(PgDialect.INSTANCE.supportedFunctions.contains("date_trunc"))
    }
}
