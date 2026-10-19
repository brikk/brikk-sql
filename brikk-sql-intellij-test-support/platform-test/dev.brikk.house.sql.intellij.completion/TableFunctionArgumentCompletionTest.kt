package dev.brikk.house.sql.intellij.completion

import com.intellij.util.ThreeState
import dev.brikk.house.sql.metadata.DORIS_METADATA

class DorisConfidence : SqlCompletionConfidence(dorisProfile)

/** Property completion is off for now ([PROPERTY_COMPLETION]): a table function's arguments offer no keys or values. */
class TableFunctionArgumentCompletionTest : CompletionTestCase() {
    private val function = DORIS_METADATA.default.tableFunctions.orEmpty().first { tf -> tf.properties.any { it.values.isNotEmpty() } }
    private val property = function.properties.first { it.values.isNotEmpty() }

    private fun offered(text: String): List<String> {
        configure(text, DorisCompletion::class.java)
        return complete().map { it.lookupString }
    }

    fun testNoPropertyNamesOrValuesAreOfferedInsideTheCall() {
        val names = function.properties.map { it.name }
        assertDoesntContain(offered("SELECT * FROM ${function.name}('<caret>')"), names)
        assertDoesntContain(offered("SELECT * FROM ${function.name}(${property.name.take(3)}<caret>)"), names)
        assertDoesntContain(offered("SELECT * FROM ${function.name}('${property.name}' = '<caret>')"), property.values)
    }

    fun testFunctionsAreStillOffered() {
        assertContainsElements(offered("SELECT ab<caret> FROM t").map(String::lowercase), "abs")
    }

    private fun confidence(text: String): ThreeState {
        myFixture.configureByText("confidence.sql", text.replace("<caret>", ""))
        val offset = text.indexOf("<caret>")
        return DorisConfidence().shouldSkipAutopopup(myFixture.editor, myFixture.file.findElementAt(offset - 1)!!, myFixture.file, offset)
    }

    fun testTheAutopopupIsNotForcedInsideTheCall() {
        assertEquals(ThreeState.UNSURE, confidence("SELECT * FROM ${function.name}('${property.name.take(2)}<caret>')"))
    }

    fun testTheDigitGateStillDecides() {
        dorisProfile.functionGates = SqlFunctionGates(digitPrefix = true)
        assertEquals(ThreeState.YES, confidence("SELECT a FROM t GROUP BY 1<caret>"))
    }
}
