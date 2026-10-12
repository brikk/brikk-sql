package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.Language
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlCompositeElementTypes
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureParserDefinition
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase

private val ROOT = IElementType("GATE_TEST_ROOT", Language.ANY)
private val GRAMMAR_STATEMENT = IElementType("GATE_TEST_STATEMENT", Language.ANY)

/** The gate over a hand-written grammar on a plain platform builder, no Grammar-Kit involved. */
class StatementBoundaryGateTest : CHouseFixtureTestCase() {
    /** Parses [text] with [statement] as the grammar's statement rule behind a gate: each top-level node's type and text. */
    private fun parse(text: String, statement: PsiBuilder.() -> Boolean): List<Pair<IElementType, String>> {
        val definition = CHouseFixtureParserDefinition()
        val builder = PsiBuilderFactory.getInstance().createBuilder(definition, definition.createLexer(project), text)
        val gate = StatementBoundaryGate("clickhouse")
        val root = builder.mark()
        while (!builder.eof()) {
            val at = builder.currentOffset
            if (builder.tokenText == ";" || !gate.parse(builder) { builder.statement() } || builder.currentOffset == at) builder.advanceLexer()
        }
        root.done(ROOT)
        return generateSequence(builder.treeBuilt.firstChildNode) { it.treeNext }
            .filter { it.elementType == GRAMMAR_STATEMENT || it.elementType == SqlCompositeElementTypes.SQL_STATEMENT }
            .map { it.elementType to it.text }.toList()
    }

    /** A grammar whose statements end at a line end, as a recovering grammar ends one without `;`; [error] reports an error in it. */
    private fun PsiBuilder.lineStatement(error: Boolean = false): Boolean {
        val marker = mark()
        do advanceLexer() while (!eof() && tokenText != ";" && '\n' !in originalText.subSequence(rawTokenTypeStart(-1), currentOffset))
        if (error) error("grammar gap")
        marker.done(GRAMMAR_STATEMENT)
        return true
    }

    fun testACleanSplitWithoutASemicolonStays() {
        assertEquals(listOf(GRAMMAR_STATEMENT to "SELECT 1", GRAMMAR_STATEMENT to "SELECT 2"), parse("SELECT 1\nSELECT 2;") { lineStatement() })
    }

    fun testAnErrorBeforeTheSplitJoinsTheRange() {
        assertEquals(listOf(SqlCompositeElementTypes.SQL_STATEMENT to "SELECT 1\nSELECT 2"), parse("SELECT 1\nSELECT 2;") { lineStatement(error = true) })
    }

    fun testASplitInsideBracketsJoinsTheRange() {
        assertEquals(listOf(SqlCompositeElementTypes.SQL_STATEMENT to "SELECT f(1\n, 2)"), parse("SELECT f(1\n, 2);") { lineStatement() })
    }

    fun testParsingNothingJoinsTheRange() {
        assertEquals(listOf(SqlCompositeElementTypes.SQL_STATEMENT to "SHOW x\nSELECT 2"), parse("SHOW x\nSELECT 2;") { false })
    }

    fun testRunningPastTheRangeIsCutToIt() {
        val overreaching: PsiBuilder.() -> Boolean = {
            val marker = mark()
            while (!eof()) advanceLexer()
            marker.done(GRAMMAR_STATEMENT)
            true
        }
        assertEquals(listOf(SqlCompositeElementTypes.SQL_STATEMENT to "SELECT 1", GRAMMAR_STATEMENT to "SELECT 2"),
            parse("SELECT 1; SELECT 2") { overreaching() })
    }
}
