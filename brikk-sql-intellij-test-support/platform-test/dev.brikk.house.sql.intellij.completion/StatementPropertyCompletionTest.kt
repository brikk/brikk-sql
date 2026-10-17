package dev.brikk.house.sql.intellij.completion

import com.intellij.util.ThreeState

/** Property completion is off for now ([PROPERTY_COMPLETION]): a statement's PROPERTIES offer no keys or values. */
class StatementPropertyCompletionTest : CompletionTestCase() {
    private fun offered(text: String): List<String> {
        configure(text, DorisCompletion::class.java)
        return complete().map { it.lookupString }
    }

    fun testNoKeysOrValuesAreOfferedInAPropertiesList() {
        assertDoesntContain(offered("CREATE TABLE t (k INT) PROPERTIES ('replication_num' = '1', '<caret>')"), "storage_medium", "light_schema_change")
        assertDoesntContain(offered("CREATE TABLE t (k INT) PROPERTIES (storage_med<caret>)"), "storage_medium")
        assertDoesntContain(offered("CREATE TABLE t (k INT) PROPERTIES ('storage_medium' = '<caret>')"), "SSD", "HDD")
        assertDoesntContain(offered("CREATE CATALOG c PROPERTIES ('type' = '<caret>')"), "hms", "iceberg")
        assertDoesntContain(offered("CREATE ROUTINE LOAD db.j ON t FROM KAFKA ('kafka_<caret>')"), "kafka_topic")
    }

    fun testTheAutopopupIsNotForcedInsideAListsStrings() {
        val text = "CREATE TABLE t (k INT) PROPERTIES ('st<caret>')"
        myFixture.configureByText("confidence.sql", text.replace("<caret>", ""))
        val offset = text.indexOf("<caret>")
        assertEquals(ThreeState.UNSURE, DorisConfidence().shouldSkipAutopopup(myFixture.file.findElementAt(offset - 1)!!, myFixture.file, offset))
    }
}
