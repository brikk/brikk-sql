package dev.brikk.house.sql.intellij.editor

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.ParserDefinition
import com.intellij.lang.annotation.HighlightSeverity
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase
import dev.brikk.house.sql.intellij.parser.MaskingCHouseParserDefinition

/** A masked `PARTITION BY` bind the grammar fails on shows no error under LENIENT_ONLY; a real error still does. */
class MaskedBindErrorFilterTest : CHouseFixtureTestCase() {
    override fun parserDefinition(): ParserDefinition = MaskingCHouseParserDefinition()

    fun testAMaskedBindStatementShowsNoErrorButAGenuineOneDoes() {
        HighlightErrorFilter.EP_NAME.getPoint(project).registerExtension(LenientErrorFilter(), testRootDisposable)
        val export = "INSERT INTO FUNCTION null('x String') PARTITION BY concat(:prefix, 'a') SELECT :q;\nSHOW x y;\n"
        configureCHouse(export + "SELECT 1 FROM;")
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        assertNotEmpty(errors)
        assertTrue(errors.map { it.startOffset }.toString(), errors.all { it.startOffset >= export.length })
    }
}
