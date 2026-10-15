package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.util.ThreeState

class StatementPropertyCompletionTest : CompletionTestCase() {
    private fun offered(text: String): List<String> {
        configure(text, DorisCompletion::class.java)
        return complete().map { it.lookupString }
    }

    fun testATablesPropertiesOfferTheKeysItHasNotSet() {
        val items = offered("CREATE TABLE t (k INT) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES ('replication_num' = '1', '<caret>')")
        assertContainsElements(items, "storage_medium", "light_schema_change", "compaction_policy")
        assertDoesntContain(items, "replication_num", "kafka_topic")
    }

    fun testAnAsyncViewOffersItsOwnAndItsModelsTableKeysOnce() {
        val items = offered(
            "CREATE MATERIALIZED VIEW mv BUILD IMMEDIATE REFRESH AUTO ON MANUAL DISTRIBUTED BY RANDOM BUCKETS 1 " +
                "PROPERTIES ('grace_period' = '10', '<caret>') AS SELECT 1 AS k",
        )
        assertContainsElements(items, "replication_num", "storage_medium", "storage_format", "inverted_index_storage_format", "file_cache_ttl_seconds", "use_for_rewrite")
        assertDoesntContain(items, "grace_period", "function_column.sequence_col", "enable_unique_key_merge_on_write", "dynamic_partition.enable")
        assertEquals(items.distinct(), items)
    }

    fun testKnownValuesAreOfferedRightOfEquals() {
        val items = offered("CREATE TABLE t (k INT) PROPERTIES ('storage_medium' = '<caret>')")
        assertSameElements(items.filter { it in setOf("SSD", "HDD", "storage_medium") }, "SSD", "HDD")
    }

    fun testEachListOffersItsOwnKeys() {
        assertContainsElements(offered("CREATE ROUTINE LOAD db.j ON t FROM KAFKA ('kafka_<caret>')"), "kafka_topic", "kafka_broker_list")
        assertContainsElements(offered("SELECT * FROM t INTO OUTFILE 's3://b/p' FORMAT AS csv PROPERTIES ('<caret>')"), "column_separator", "max_file_size")
        assertContainsElements(offered("CREATE CATALOG c PROPERTIES ('type' = 'hms', 'hive.<caret>')"), "hive.metastore.uris")
        assertContainsElements(offered("CREATE CATALOG c PROPERTIES ('type' = '<caret>')"), "hms", "iceberg", "jdbc")
    }

    fun testAKeyShowsTheListItBelongsTo() {
        configure("CREATE TABLE t (k INT) PROPERTIES ('storage_<caret>')", DorisCompletion::class.java)
        val item = complete().first { it.lookupString == "storage_medium" }
        assertEquals("CREATE TABLE property", LookupElementPresentation.renderElement(item).typeText)
    }

    fun testAKeyTypedOutsideAStringIsInsertedQuoted() {
        dorisProfile.propertyQuote = '"'
        configure("CREATE TABLE t (k INT) PROPERTIES (storage_med<caret>)", DorisCompletion::class.java)
        val items = complete()
        if (items.isNotEmpty()) {
            myFixture.lookup.currentItem = items.first { it.lookupString == "storage_medium" }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
        assertEquals("CREATE TABLE t (k INT) PROPERTIES (\"storage_medium\"<caret>)", resultWithCaret())
    }

    fun testNothingIsOfferedOutsideAList() {
        assertDoesntContain(offered("SELECT '<caret>' FROM t"), "storage_medium", "replication_num")
        assertDoesntContain(offered("INSERT INTO t VALUES ('<caret>')"), "storage_medium")
    }

    fun testTheAutopopupIsKeptInsideAListsStrings() {
        val text = "CREATE TABLE t (k INT) PROPERTIES ('st<caret>')"
        myFixture.configureByText("confidence.sql", text.replace("<caret>", ""))
        val offset = text.indexOf("<caret>")
        assertEquals(ThreeState.NO, DorisConfidence().shouldSkipAutopopup(myFixture.file.findElementAt(offset - 1)!!, myFixture.file, offset))
    }
}
