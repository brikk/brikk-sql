package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlNameContextTest {
    private fun of(before: String, dialect: String = "duckdb") = checkNotNull(SqlNameContext.of(before, dialect))

    @Test
    fun codeKeepsTheStatementsTokensAndPreviousToken() {
        val context = of("SELECT 1;\nSELECT a, ")
        assertTrue(context.inCode)
        assertEquals("\nSELECT a, ", context.statement)
        assertEquals(listOf("SELECT", "a", ","), context.tokens.map { it.text })
        assertEquals(",", context.previous)
    }

    @Test
    fun stringsCommentsAndUnterminatedTextAreNoCode() {
        for (before in listOf("SELECT '", "SELECT 1 -- ", "SELECT /* ", "SELECT 'a', \"b")) {
            val context = of(before)
            assertFalse(context.inCode, before)
            assertTrue(context.tokens.isEmpty(), before)
        }
    }

    @Test
    fun clickhouseInlineDataAndHashCommentsAreNoCode() {
        assertFalse(of("INSERT INTO t FORMAT TSV\na\t", "clickhouse").inCode)
        assertFalse(of("SELECT 1 # ", "clickhouse").inCode)
    }

    @Test
    fun theQualifierIsTheAdjacentDottedChain() {
        assertEquals("db.\"t\"", of("FROM events |> WHERE db.\"t\".").qualifier)
        assertEquals("t", of("SELECT t.").qualifier)
        assertNull(of("SELECT t . ").qualifier)
        assertNull(of("SELECT ").qualifier)
    }

    @Test
    fun anUnknownDialectHasNoContext() {
        assertNull(SqlNameContext.of("SELECT ", "no-such-dialect"))
    }
}
