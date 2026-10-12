package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.util.ThreeState
import dev.brikk.house.sql.metadata.DORIS_METADATA

class DorisConfidence : SqlCompletionConfidence(dorisProfile)

class TableFunctionArgumentCompletionTest : CompletionTestCase() {
    private val function = DORIS_METADATA.default.tableFunctions.orEmpty().first { tf -> tf.properties.any { it.values.isNotEmpty() } }
    private val property = function.properties.first { it.values.isNotEmpty() }

    private fun offered(text: String): List<String> {
        configure(text, DorisCompletion::class.java)
        return complete().map { it.lookupString }
    }

    fun testPropertyNamesAreOfferedInsideTheCall() {
        assertContainsElements(offered("SELECT * FROM ${function.name}('<caret>')"), function.properties.map { it.name })
    }

    fun testKnownValuesAreOfferedRightOfEquals() {
        val items = offered("SELECT * FROM ${function.name}('${property.name}' = '<caret>')")
        assertContainsElements(items, property.values)
        assertDoesntContain(items, function.properties.map { it.name } - property.values.toSet())
    }

    fun testNothingIsOfferedOutsideTheCall() {
        assertDoesntContain(offered("SELECT '<caret>' FROM ${function.name}()"), function.properties.map { it.name })
    }

    private fun accept(text: String, name: String): String {
        configure(text, DorisCompletion::class.java)
        val items = complete()
        myFixture.lookup.currentItem = items.first { it.lookupString == name }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return resultWithCaret()
    }

    fun testAPropertyTypedOutsideAStringIsInsertedInTheProfilesQuote() {
        dorisProfile.propertyQuote = '"'
        val typed = property.name.take(3)
        assertEquals("SELECT * FROM ${function.name}(\"${property.name}\"<caret>)",
            accept("SELECT * FROM ${function.name}($typed<caret>)", property.name))
    }

    fun testAPropertyTypedInAStringCompletesInsideIt() {
        val typed = property.name.take(3)
        assertEquals("SELECT * FROM ${function.name}('${property.name}<caret>')",
            accept("SELECT * FROM ${function.name}('$typed<caret>')", property.name))
    }

    private fun confidence(text: String): ThreeState {
        myFixture.configureByText("confidence.sql", text.replace("<caret>", ""))
        val offset = text.indexOf("<caret>")
        return DorisConfidence().shouldSkipAutopopup(myFixture.file.findElementAt(offset - 1)!!, myFixture.file, offset)
    }

    fun testTheAutopopupIsKeptInsideTheCallsStrings() {
        assertEquals(ThreeState.NO, confidence("SELECT * FROM ${function.name}('${property.name.take(2)}<caret>')"))
        assertEquals(ThreeState.UNSURE, confidence("SELECT '${property.name.take(2)}<caret>' FROM t"))
    }
}
