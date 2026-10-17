package dev.brikk.house.sql.shape

import dev.brikk.house.sql.parser.sourceOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MaskNamedParametersTest {
    @Test
    fun namedPlaceholdersBecomePaddedScalarsAtTheSameOffsets() {
        for ((dialect, sql, masked) in listOf(
            Triple("duckdb", "SELECT '\uD83D\uDE00:x', a FROM t WHERE a = :a AND b = \$bee AND c = ?",
                "SELECT '\uD83D\uDE00:x', a FROM t WHERE a = 0  AND b = 0    AND c = ?"),
            Triple("duckdb", "SELECT :n::INTEGER AS n", "SELECT 0 ::INTEGER AS n"),
            Triple("trino", "SELECT * FROM t\r\nWHERE a = :long_name -- :c\r\nLIMIT :n",
                "SELECT * FROM t\r\nWHERE a = 0          -- :c\r\nLIMIT 0 "),
            Triple("doris", "FROM t |> WHERE a = :a AND b = @b", "FROM t |> WHERE a = 0  AND b = @b"),
        )) {
            val result = maskNamedParameters(sql, dialect)
            assertEquals(masked, result, dialect)
            assertTrue(parseParameterOccurrences(result, dialect).none { it.style == ParamStyle.NAMED_COLON }, dialect)
        }
    }

    @Test
    fun lineBreaksInsideAPlaceholderSurviveSoNativePositionsStillMap() {
        val sql = "SELECT :\r\nname, bad FROM t"
        val masked = maskNamedParameters(sql, "duckdb")
        assertEquals("SELECT 0\r\n    , bad FROM t", masked)
        val badColumn = sql.indexOf("bad") - sql.indexOf('\n')
        assertEquals(sql.indexOf("bad"), sourceOffset(masked, 2, badColumn))
    }

    @Test
    fun sqlWithoutNamedPlaceholdersIsUnchanged() {
        val sql = "SELECT a FROM t WHERE b = ?"
        assertEquals(sql, maskNamedParameters(sql, "trino"))
    }

    @Test
    fun refusalsPropagate() {
        assertFailsWith<ShapeError> { maskNamedParameters("SELECT :a; SELECT :b", "duckdb") }
    }
}
