package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** A helper's driver properties win over the same settings in the data source's URL, for the helper only. */
class HelperConnectionPointTest : BasePlatformTestCase() {
    private val uncompressed = mapOf("compress" to "false", "decompress" to "false")

    private fun point(url: String, properties: Map<String, String> = uncompressed): Pair<LocalDataSource, HelperConnectionPoint> {
        val dataSource = LocalDataSource().apply { this.url = url }
        return dataSource to HelperConnectionPoint(dataSource, properties)
    }

    fun testAConflictingUrlParameterTakesTheHelpersValue() {
        val url = "jdbc:clickhouse://h:8123/default?compress=true&ignore_unknown_config_key=true&decompress=1"
        val (dataSource, helper) = point(url)
        assertEquals("jdbc:clickhouse://h:8123/default?compress=false&ignore_unknown_config_key=true&decompress=false", helper.url)
        assertEquals("the data source keeps its URL", url, dataSource.url)
        assertEquals("false", helper.connectionProperties.getProperty("compress"))
    }

    fun testOtherUrlsStayAsWritten() {
        for (url in listOf(
            "jdbc:clickhouse://h:8123/default",
            "jdbc:clickhouse://h:8123/default?ignore_unknown_config_key=true",
            "jdbc:clickhouse://h:8123/default?compression=true",
            "jdbc:duckdb:/tmp/a.db",
        )) assertEquals(url, point(url).second.url)
        assertEquals("no properties, no change", "jdbc:x://h/?compress=true", point("jdbc:x://h/?compress=true", emptyMap()).second.url)
    }

    fun testEncodedKeysAndValuesAreMatchedAndWritten() {
        val (_, helper) = point("jdbc:x://h/db?comp%72ess=true&a=b#frag", mapOf("compress" to "a&b"))
        assertEquals("jdbc:x://h/db?compress=a%26b&a=b#frag", helper.url)
    }
}
