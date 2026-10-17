package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals

class ContainsPipeTest {
    @Test
    fun containsPipeSeesNestedPipelinesThatIsPipeDoesNot() {
        for ((sql, expected) in listOf(
            "FROM t |> WHERE a > 1" to (true to true),
            "SELECT * FROM (FROM t |> SELECT a) AS s" to (false to true),
            "WITH s AS (FROM t |> SELECT a) SELECT * FROM s" to (false to true),
            "SELECT * FROM t WHERE a IN (SELECT a FROM (FROM u |> WHERE b) AS v)" to (false to true),
            "SELECT '|>' AS a FROM t" to (false to false),
            "FROM t" to (false to false),
        )) {
            for (dialect in listOf("duckdb", "trino", "doris")) {
                val fragment = SqlFragment(sql, dialect)
                assertEquals(expected, fragment.isPipe to fragment.containsPipe, "$dialect: $sql")
            }
        }
    }
}
