package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.PipeStageCut
import dev.brikk.house.sql.parser.PipeStageSplitter
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LowerForExecutionTest {
    private val dialects = listOf("duckdb", "trino", "doris")

    private fun assertLowered(text: String, dialect: String, allowFirstStage: Boolean = false): PipeLowering.Ok {
        val lowered = assertIs<PipeLowering.Ok>(lowerForExecution(text, dialect, allowFirstStage), "$dialect: $text")
        assertEquals(SqlFragment(text, dialect).toExecutable(dialect, pretty = true).sql, lowered.sql)
        assertTrue(Dialects.forName(dialect).tokenize(lowered.sql).none { it.tokenType == TokenType.PIPE_GT }, lowered.sql)
        assertIs<Query>(Dialects.forName(dialect).parseOne(lowered.sql).also { assertNull(it.find<PipeQuery>()) })
        return lowered
    }

    private fun assertRefused(text: String, dialect: String, allowFirstStage: Boolean = false): PipeLowering.Refused =
        assertIs<PipeLowering.Refused>(lowerForExecution(text, dialect, allowFirstStage), "$dialect: $text")

    @Test
    fun rootAndNestedPipesLowerToStandardSql() {
        for (dialect in dialects) {
            for (text in listOf(
                "FROM t |> WHERE a > 1 |> SELECT a;",
                "-- lead\nFROM t |> WHERE a > 1; -- trail\n",
                "SELECT * FROM (FROM t |> WHERE a > 1) AS s",
                "WITH s AS (FROM t |> SELECT a) SELECT a FROM s",
            )) {
                assertTrue(assertLowered(text, dialect).blockers.isEmpty(), "$dialect: $text")
            }
        }
    }

    @Test
    fun unsupportedPivotIsRefused() {
        for (dialect in listOf("trino", "doris")) {
            for (stage in listOf("PIVOT (SUM(x) FOR k IN ('a'))", "UNPIVOT (v FOR k IN (a, b))")) {
                assertNull(assertRefused("FROM t |> $stage", dialect).range)
            }
        }
    }

    @Test
    fun textWithoutPipesIsNotPipeUnlessTheFirstStageIsAllowed() {
        for (dialect in dialects) {
            assertEquals(PipeLowering.NotPipe, lowerForExecution("SELECT 1", dialect))
            assertEquals(PipeLowering.NotPipe, lowerForExecution("SELECT '|>' AS a FROM t", dialect))
            assertEquals(PipeLowering.NotPipe, lowerForExecution("FROM t", dialect))
            assertLowered("FROM t", dialect, allowFirstStage = true)
            assertLowered("SELECT a FROM t", dialect, allowFirstStage = true)
            assertNull(assertRefused("INSERT INTO x VALUES (1)", dialect, allowFirstStage = true).range)
        }
    }

    @Test
    fun firstStagePrefixesOfAnyQueryHeadLower() {
        for (dialect in dialects) {
            for (program in listOf(
                "(FROM t) |> LIMIT 1",
                "(SELECT 1 AS a) |> WHERE a = 1",
                "SELECT 1 AS a UNION ALL SELECT 2 |> WHERE a = 1",
                "FROM t |> WHERE a = 1",
            )) {
                assertLowered(program, dialect)
                val head = assertIs<PipeStageCut.Prefix>(PipeStageSplitter.topLevelPrefixAt(program, 0, dialect), program)
                assertLowered(head.sql, dialect, allowFirstStage = true)
            }
        }
    }

    @Test
    fun boundaryErrorsAndStatementCountsAreRefused() {
        for (dialect in dialects) {
            val lexical = "SELECT 'unterminated |> WHERE a"
            assertEquals(SyntacticRange(0, lexical.length), assertRefused(lexical, dialect).range)
            assertEquals(SyntacticRange(0, 9), assertRefused("SELECT (1", dialect).range)
            val unterminated = "FROM t |> WHERE (a > 1"
            assertEquals(SyntacticRange(0, unterminated.length), assertRefused(unterminated, dialect).range)
            assertNull(assertRefused("FROM t |> WHERE a; FROM u |> WHERE b", dialect).range)
            assertNull(assertRefused("SELECT 1; FROM u |> WHERE b", dialect).range)
        }
    }

    @Test
    fun parseErrorsAreRefusedAtTheOffendingToken() {
        for (dialect in dialects) {
            val text = "-- \uD83D\uDE00\r\nFROM t\r\n|> FROBNICATE x"
            val range = assertNotNull(assertRefused(text, dialect).range)
            assertEquals("FROBNICATE", text.substring(range.start, range.end))
        }
    }

    @Test
    fun unsupportedLoweringIsRefused() {
        for (dialect in dialects) {
            assertRefused("FROM t |> LIMIT -1", dialect)
            assertRefused("FROM t |> RENAME a.b AS c", dialect)
        }
    }
}
