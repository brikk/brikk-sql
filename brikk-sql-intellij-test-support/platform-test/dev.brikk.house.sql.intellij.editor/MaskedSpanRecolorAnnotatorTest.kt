package dev.brikk.house.sql.intellij.editor

import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlCommonTokens
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.fixture.FixtureDialect
import dev.brikk.house.sql.intellij.fixture.FixtureTestCase

class FixtureRecolor : MaskedSpanRecolorAnnotator(FixtureDialect, "duckdb")

class MaskedSpanRecolorAnnotatorTest : FixtureTestCase() {
    override fun setUp() {
        super.setUp()
        LanguageAnnotators.INSTANCE.addExplicitExtension(FixtureDialect, FixtureRecolor(), testRootDisposable)
    }

    /** Forced attributes by text: the recolored ranges. */
    private fun recolored(text: String): Map<String, TextAttributesKey?> {
        configureFixture(text)
        return myFixture.doHighlighting(HighlightSeverity.INFORMATION).filter { it.forcedTextAttributesKey != null }
            .associate { text.substring(it.startOffset, it.endOffset) to it.forcedTextAttributesKey }
    }

    /** What the file's highlighter colors a platform token of [type]. */
    private fun colorOf(type: IElementType): TextAttributesKey =
        SyntaxHighlighterFactory.getSyntaxHighlighter(FixtureDialect, project, myFixture.file.virtualFile)
            .getTokenHighlights(type).lastOrNull() ?: HighlighterColors.TEXT

    fun testMaskedSpanTakesTheHighlighterColorsOfItsTokens() {
        val colors = recolored("SELECT * EXCLUDE (a, 'x', 1 /* kept */, NULL) FROM t")
        assertEquals(colorOf(SqlTokens.SQL_IDENT), colors["a"])
        assertEquals(colorOf(SqlTokens.SQL_STRING_TOKEN), colors["'x'"])
        assertEquals(colorOf(SqlTokens.SQL_INTEGER_TOKEN), colors["1"])
        assertEquals(colorOf(SqlTokens.SQL_KEYWORD_TOKEN), colors["NULL"])
        assertEquals(colorOf(SqlTokens.SQL_LEFT_PAREN), colors["("])
        assertEquals(colorOf(SqlCommonTokens.SQL_COMMA), colors[","])
        assertEquals(colorOf(SqlTokens.SQL_BLOCK_COMMENT), colors["/* kept */"])
    }

    fun testCollapsedStringAndIdentifierSpansAreRecolored() {
        val colors = recolored("SELECT {'k': 1}, x::STRUCT(a INT) FROM t")
        assertEquals(colorOf(SqlTokens.SQL_STRING_TOKEN), colors["'k'"])
        assertEquals(colorOf(SqlTokens.SQL_INTEGER_TOKEN), colors["1"])
        assertEquals(colorOf(SqlTokens.SQL_KEYWORD_TOKEN), colors["INT"])
    }

    fun testGenuineTokensAreLeftAlone() {
        val colors = recolored("SELECT 'a', E'b\\n', \"c d\", \$\$e\$\$ FROM t -- note\n/* block */")
        assertEquals("only the platform's reference colors", setOf("\"c d\"", "t"), colors.keys)
    }
}
