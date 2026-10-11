package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeywordCatalogTest {

    private fun keywords(metadata: DialectMetadata) = checkNotNull(metadata.default.keywords)

    @Test
    fun lookupIgnoresCase() {
        val catalog = KeywordCatalog(listOf(KeywordDef("select", KeywordCategory.RESERVED)))

        assertEquals(KeywordCategory.RESERVED, catalog["SeLeCt"]?.category)
        assertTrue("SELECT" in catalog)
        assertEquals(setOf("select"), catalog.reserved)
    }

    @Test
    fun aKeywordWithoutEvidenceIsUnknown() {
        assertEquals(KeywordCategory.UNKNOWN, KeywordDef("x").category)
    }

    @Test
    fun duckdbKeepsItsFourCategories() {
        val duckdb = keywords(DUCKDB_METADATA)

        assertEquals(KeywordCategory.RESERVED, duckdb["select"]?.category)
        assertTrue(duckdb.isReserved("SELECT"))
        assertFalse(duckdb.isReserved("ABORT"))
        assertEquals(KeywordCategory.UNRESERVED, duckdb["abort"]?.category)
        assertEquals(KeywordCategory.TYPE_FUNCTION, duckdb["left"]?.category)
        assertEquals(KeywordCategory.COLUMN_NAME, duckdb["between"]?.category)
    }

    @Test
    fun trinoSeparatesReservedFromNonReserved() {
        val trino = keywords(TRINO_METADATA)

        assertTrue("SELECT" in trino.reserved)
        assertEquals(KeywordCategory.UNRESERVED, trino["ADD"]?.category)
    }

    @Test
    fun dorisKeepsTheGrammarTokenSymbol() {
        val doris = keywords(DORIS_METADATA)

        assertEquals("CONVERT_LSC", doris["CONVERT_LIGHT_SCHEMA_CHANGE_PROCESS"]?.token)
        assertEquals(KeywordCategory.UNRESERVED, doris["MAPPING"]?.category)
        assertNull(doris["SET_SESSION_VARIABLE"])
    }

    @Test
    fun clickhouseReservationIsUnknown() {
        val clickhouse = keywords(CLICKHOUSE_METADATA)

        assertTrue("ADD COLUMN" in clickhouse)
        assertTrue(clickhouse.keywords.all { it.category == KeywordCategory.UNKNOWN })
        assertTrue(clickhouse.reserved.isEmpty())
    }
}
