package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.dialects.Dialects
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
        assertIs<Select>(Dialects.forName(dialect).parseOne(lowered.sql).also { assertNull(it.find<PipeQuery>()) })
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
    fun lossyLoweringIsReturnedWithBlockers() {
        for (dialect in listOf("trino", "doris")) {
            assertTrue(assertLowered("FROM t |> PIVOT (SUM(x) FOR k IN ('a'))", dialect).blockers.isNotEmpty(), dialect)
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
    fun boundaryErrorsAndStatementCountsAreRefused() {
        for (dialect in dialects) {
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
