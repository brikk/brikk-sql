package dev.brikk.house.sql

import dev.brikk.house.sql.ast.BitwiseAndAgg
import dev.brikk.house.sql.ast.BitwiseOrAgg
import dev.brikk.house.sql.ast.BitwiseXorAgg
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.metadata.CLICKHOUSE_FUNCTION_CATALOG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClickhouseAggregateCatchupTest {
    @Test
    fun canonicalAndSupportedSourceBitwiseAggregatesUseActualClickhouseNames() {
        for ((canonical, sourceName, targetName) in listOf(
            Triple("BITWISE_AND_AGG", "BIT_AND", "groupBitAnd"),
            Triple("BITWISE_OR_AGG", "BIT_OR", "groupBitOr"),
            Triple("BITWISE_XOR_AGG", "BIT_XOR", "groupBitXor"),
        )) {
            assertTrue(CLICKHOUSE_FUNCTION_CATALOG.isKnown(targetName))
            for ((dialect, call) in listOf("" to canonical, "postgres" to sourceName, "duckdb" to sourceName)) {
                val reader = Dialects.forName(dialect)
                val ast = reader.parseOne("SELECT $call(x) FROM t")
                val original = ast.copy()
                assertEquals("SELECT $targetName(x) FROM t", ast.sql("clickhouse"), "$dialect/$call")
                assertEquals(original, ast)
            }
        }
        assertTrue(Dialects.DUCKDB.parseOne("SELECT BIT_AND(x)").findAll<BitwiseAndAgg>().any())
        assertTrue(Dialects.DUCKDB.parseOne("SELECT BIT_OR(x)").findAll<BitwiseOrAgg>().any())
        assertTrue(Dialects.DUCKDB.parseOne("SELECT BIT_XOR(x)").findAll<BitwiseXorAgg>().any())
        val window = Dialects.DUCKDB.parseOne("SELECT BIT_AND(x) OVER (PARTITION BY y) FROM t").sql("clickhouse")
        assertEquals("SELECT groupBitAnd(x) OVER (PARTITION BY y) FROM t", window)
    }
}
