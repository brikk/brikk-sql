package dev.brikk.house.sql.intellij.editor

import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStringLiteralExpression
import java.util.UUID
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
        SyntaxHighlighterFactory.getSyntaxHighlighter(FixtureDialect, project, null)
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

    fun testUnterminatedQuotesTypedInASpanTakeTheirKindsColors() {
        configureFixture("SELECT * EXCLUDE (a, <caret>")
        myFixture.type("'secr")
        assertEquals(colorOf(SqlTokens.SQL_STRING_TOKEN), forced()["'secr"])
        configureFixture("SELECT * EXCLUDE (a, <caret>")
        myFixture.type("\"secr")
        assertEquals(colorOf(SqlTokens.SQL_IDENT_DELIMITED), forced()["\"secr"])
    }

    private fun forced(): Map<String, TextAttributesKey?> {
        val text = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.INFORMATION).filter { it.forcedTextAttributesKey != null }
            .associate { text.substring(it.startOffset, it.endOffset) to it.forcedTextAttributesKey }
    }

    fun testGenuineTokensAreLeftAlone() {
        val colors = recolored("SELECT 'a', E'b\\n', \"c d\", \$\$e\$\$ FROM t -- note\n/* block */")
        assertEquals("only the platform's reference colors", setOf("\"c d\"", "t"), colors.keys)
    }

    /** A place of the injection: a host literal, its range, and the placeholder text after it that no host holds. */
    private class Place(val literal: Int, val range: (SqlStringLiteralExpression) -> TextRange, val suffix: String? = null)

    /**
     * Injects the fixture dialect into the string literals of the SQL [host], at [places], as Kotlin
     * hosts inject: interpolations become placeholders between fragments and a trimmed multiline
     * string leaves its indentation out. Returns the recolored host ranges by host text.
     */
    private fun injectedColors(host: String, vararg places: Place): Map<String, TextAttributesKey?> {
        myFixture.configureByText("host-${UUID.randomUUID()}.sql", host)
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(object : MultiHostInjector {
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                val literals = PsiTreeUtil.findChildrenOfType(context.containingFile, SqlStringLiteralExpression::class.java).toList()
                if (literals.firstOrNull() !== context) return
                registrar.startInjecting(FixtureDialect)
                for (place in places) literals[place.literal].let { registrar.addPlace(null, place.suffix, it, place.range(it)) }
                registrar.doneInjecting()
            }

            override fun elementsToInjectIn() = listOf(SqlStringLiteralExpression::class.java)
        }, testRootDisposable)
        return myFixture.doHighlighting(HighlightSeverity.INFORMATION).filter { it.forcedTextAttributesKey != null }
            .associate { host.substring(it.startOffset, it.endOffset) to it.forcedTextAttributesKey }
    }

    private val inner = { literal: SqlStringLiteralExpression -> TextRange(1, literal.textLength - 1) }

    fun testInjectedSpansReadTheUnescapedText() {
        val colors = injectedColors("SELECT 'SELECT {''k'': 1}, * EXCLUDE (a /* c */) FROM t'", Place(0, inner))
        assertEquals(colors.toString(), colorOf(SqlTokens.SQL_STRING_TOKEN), colors["''k''"])
        assertEquals(colorOf(SqlTokens.SQL_INTEGER_TOKEN), colors["1"])
        assertNull(colors["''"])
        assertEquals(colorOf(SqlTokens.SQL_IDENT), colors["a"])
        assertEquals(colorOf(SqlTokens.SQL_BLOCK_COMMENT), colors["/* c */"])
    }

    fun testAnInjectedSpanAroundAnInterpolationIsRecoloredInBothFragments() {
        val colors = injectedColors("SELECT 'SELECT {''k'': ' || ', ''v'': 2}'", Place(0, inner, suffix = "0"), Place(1, inner))
        assertEquals(colors.toString(), colorOf(SqlTokens.SQL_STRING_TOKEN), colors["''k''"])
        assertEquals(colorOf(SqlTokens.SQL_STRING_TOKEN), colors["''v''"])
        assertEquals(colorOf(SqlTokens.SQL_INTEGER_TOKEN), colors["2"])
    }

    fun testAnInjectedSpanAcrossATrimmedMultilineStringIsRecolored() {
        val host = "SELECT 'SELECT {''k'':\n    1}'"
        val lineBreak = host.indexOf('\n') - host.indexOf('\'')
        val colors = injectedColors(host,
            Place(0, { TextRange(1, lineBreak + 1) }),
            Place(0, { literal -> TextRange(lineBreak + 5, literal.textLength - 1) }))
        assertEquals(colors.toString(), colorOf(SqlTokens.SQL_STRING_TOKEN), colors["''k''"])
        assertEquals(colorOf(SqlTokens.SQL_INTEGER_TOKEN), colors["1"])
    }
}
