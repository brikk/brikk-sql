package dev.brikk.house.sql.shape

import dev.brikk.house.sql.parser.SqlScriptSnapshot
import kotlin.test.*

class EditorContractTest {
    @Test fun routineAndQuotedSeparators() {
        val sql = "CREATE FUNCTION f(x bigint) RETURNS bigint BEGIN IF x > 0 THEN RETURN 1; END IF; RETURN 0; END; SELECT ';|>'; FROM t |> SELECT x; -- tail"
        val ranges = SqlScriptSnapshot.parse(sql, "trino").statements
        assertEquals(4, ranges.size)
        assertTrue(sql.substring(ranges[0].start, ranges[0].end).endsWith("END;"))
        assertFalse(ranges[1].hasPipe)
        assertTrue(ranges[2].hasPipe)
        assertFalse(ranges[3].hasSql)
        assertTrue(ranges.all { it.boundaryError == null })
        assertEquals(sql, ranges.joinToString("") { sql.substring(it.start, it.end) })
    }

    @Test fun opaqueMalformedTailAndUnicode() {
        val sql = "SELECT '😀'; FROM t |> WHERE x = 'unterminated; SELECT 2 |> LIMIT 1"
        val ranges = SqlScriptSnapshot.parse(sql, "trino").statements
        assertEquals(2, ranges.size)
        assertEquals(sql.indexOf(';') + 1, ranges[0].end)
        assertTrue(ranges[1].hasPipe)
        assertNotNull(ranges[1].boundaryError)
        assertFalse(SqlScriptSnapshot.parse("SELECT 'broken |> hidden", "trino").statements.single().hasPipe)
    }

    @Test fun duckdbDollarQuotesAndComments() {
        val sql = "SELECT $$;|>$$; /* outer /* ; |> */ inner */ FROM t |> LIMIT 1;"
        val ranges = SqlScriptSnapshot.parse(sql, "duckdb").statements
        assertEquals(2, ranges.size)
        assertFalse(ranges[0].hasPipe)
        assertTrue(ranges[1].hasPipe)
    }

    @Test fun parametersWithoutLowering() {
        val sql = "FROM t |> WHERE x = :value OR y = :value |> SELECT ':not_a_bind' AS s"
        val params = parseParameterOccurrences(sql, "trino")
        assertEquals(listOf("value", "value"), params.map { it.name })
        assertTrue(params.all { sql.substring(it.range.start, it.range.end) == ":value" })
        assertTrue(params.all { sql.substring(it.nameRange!!.start, it.nameRange!!.end) == "value" })
        assertFails { parseParameterOccurrences("FROM t |> BOGUS :x", "trino") }
    }

    @Test fun reservedRowsSlotAndWindowRowsRemainDistinct() {
        for (dialect in listOf("trino", "duckdb", "doris")) {
            val fragment = SqlFragment("SELECT r.code FROM rows() AS r", dialect)
            assertEquals(listOf("rows"), fragment.tableSlots)
            SqlFragment("SELECT sum(x) OVER (ORDER BY y ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM t", dialect).ast
        }
    }

    @Test fun parenthesizedControlConditionAndScalarIfDoNotSplitRoutine() {
        val sql = "CREATE FUNCTION f(x bigint) RETURNS bigint BEGIN IF (x > 0) THEN RETURN IF(x > 1, 2, 1); END IF; RETURN 0; END; SELECT 2;"
        val ranges = SqlScriptSnapshot.parse(sql, "trino").statements
        assertEquals(2, ranges.size)
        assertNull(ranges[0].boundaryError)
    }
}
