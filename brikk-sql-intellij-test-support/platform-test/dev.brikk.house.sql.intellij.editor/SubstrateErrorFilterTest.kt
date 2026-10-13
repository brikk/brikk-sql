package dev.brikk.house.sql.intellij.editor

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlCompositeElementTypes
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureDialect
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase
import dev.brikk.house.sql.intellij.parser.CHouseFamilyParser
import dev.brikk.house.sql.intellij.parser.CHouseFamilyParserDefinition
import dev.brikk.house.sql.intellij.parser.consumeLenient
import dev.brikk.house.sql.intellij.parser.wordAt

/** A lenient fallback that records where the grammar gave up, as a plugin's statement rules may. */
class ErrorRecordingParser : CHouseFamilyParser() {
    override fun parseStatement(builder: PsiBuilder, level: Int): Boolean {
        if (builder.wordAt(0) != "SHOW") return super.parseStatement(builder, level)
        val statement = builder.mark()
        builder.advanceLexer()
        builder.error("not in the grammar")
        builder.consumeLenient()
        statement.done(SqlCompositeElementTypes.SQL_STATEMENT)
        return true
    }
}

class ErrorRecordingDefinition : CHouseFamilyParserDefinition(CHouseFixtureDialect) {
    override fun createParser(project: Project?): PsiParser = ErrorRecordingParser()
}

class LenientErrorFilter : SubstrateErrorFilter(CHouseFixtureDialect, SubstrateErrors.LENIENT_ONLY)
class AllErrorFilter : SubstrateErrorFilter(CHouseFixtureDialect)

/** On a stock substrate the grammar's errors are the dialect's, so only lenient fallbacks hide theirs. */
class SubstrateErrorFilterTest : CHouseFixtureTestCase() {
    override fun parserDefinition(): ParserDefinition = ErrorRecordingDefinition()

    /** The error inside the lenient SHOW and the grammar's own errors in the SELECT. */
    private fun errors(): Pair<PsiErrorElement, List<PsiErrorElement>> {
        configureCHouse("SHOW x y;\nSELECT 1 FROM;")
        val (lenient, grammar) = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
            .partition { it.textOffset < myFixture.editor.document.text.indexOf(';') }
        assertNotEmpty(grammar)
        return lenient.single() to grammar
    }

    fun testLenientOnlyHidesErrorsInLenientFallbacks() {
        val (lenient, grammar) = errors()
        val filter = LenientErrorFilter()
        assertFalse(filter.shouldHighlightErrorElement(lenient))
        assertTrue(grammar.all(filter::shouldHighlightErrorElement))
        HighlightErrorFilter.EP_NAME.getPoint(project).registerExtension(filter, testRootDisposable)
        val highlighted = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.startOffset }
        assertNotEmpty(highlighted)
        assertTrue(highlighted.none { it < myFixture.editor.document.text.indexOf(';') })
    }

    fun testAllHidesEveryError() {
        val (lenient, grammar) = errors()
        val filter = AllErrorFilter()
        assertTrue((grammar + lenient).none(filter::shouldHighlightErrorElement))
    }
}
