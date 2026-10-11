package dev.brikk.house.sql

import dev.brikk.house.sql.parser.PipeStageCut
import dev.brikk.house.sql.parser.PipeStageSplitter
import kotlin.test.Test
import kotlin.test.assertEquals

class PipeStagePrefixTest {
    private val dialects = listOf("duckdb", "trino", "doris")

    private fun assertCut(expected: PipeStageCut, sql: String, offset: Int) {
        for (dialect in dialects) {
            assertEquals(expected, PipeStageSplitter.topLevelPrefixAt(sql, offset, dialect), "$dialect at $offset: $sql")
        }
    }

    @Test
    fun stagesOwnHalfOpenRangesUpToTheNextStage() {
        val sql = "FROM t\n|> WHERE a > 1\n|> SELECT a, (SELECT 1) AS b\n|> LIMIT 5"
        val where = PipeStageCut.Prefix("FROM t\n|> WHERE a > 1", 1, 4)
        assertCut(PipeStageCut.Prefix("FROM t", 0, 4), sql, 0)
        assertCut(PipeStageCut.Prefix("FROM t", 0, 4), sql, sql.indexOf("WHERE") - 1)
        assertCut(where, sql, sql.indexOf("WHERE"))
        assertCut(where, sql, sql.indexOf("\n|> SELECT"))
        assertCut(where, sql, sql.indexOf("|> SELECT"))
        assertCut(PipeStageCut.Prefix("FROM t\n|> WHERE a > 1\n|> SELECT a, (SELECT 1) AS b", 2, 4), sql, sql.indexOf("SELECT 1"))
        assertCut(PipeStageCut.Prefix(sql, 3, 4), sql, sql.length)
    }

    @Test
    fun caretsInsideBracketedPipelinesAreRefused() {
        val sql = "FROM t |> JOIN (FROM u |> WHERE b) USING (id) |> SELECT a"
        assertCut(PipeStageCut.InsideNestedPipeline, sql, sql.indexOf("WHERE b"))
        assertCut(PipeStageCut.InsideNestedPipeline, sql, sql.indexOf('('))
        assertCut(PipeStageCut.InsideNestedPipeline, sql, sql.indexOf(')'))
        assertCut(PipeStageCut.Prefix("FROM t |> JOIN (FROM u |> WHERE b) USING (id)", 1, 3), sql, sql.indexOf(')') + 1)
        assertCut(PipeStageCut.Prefix("FROM t |> JOIN (FROM u |> WHERE b) USING (id)", 1, 3), sql, sql.indexOf("id"))

        val cte = "WITH s AS (FROM u |> WHERE b) FROM s |> SELECT a"
        assertCut(PipeStageCut.InsideNestedPipeline, cte, cte.indexOf("WHERE"))

        val unclosed = "FROM t |> WHERE a IN (FROM u |> WHERE b"
        assertCut(PipeStageCut.InsideNestedPipeline, unclosed, unclosed.length)
    }

    @Test
    fun programsWithoutTwoStagesAndOffsetsOutsideStagesAreUnavailable() {
        assertCut(PipeStageCut.Unavailable, "FROM t", 2)
        assertCut(PipeStageCut.Unavailable, "SELECT '|>' FROM t", 2)
        val commented = "-- lead\nFROM t |> WHERE a"
        assertCut(PipeStageCut.Unavailable, commented, 1)
        assertCut(PipeStageCut.Unavailable, commented, -1)
        assertCut(PipeStageCut.Unavailable, commented, commented.length + 1)
        assertCut(PipeStageCut.Unavailable, "FROM t |> WHERE a = 'open", 10)
    }
}
