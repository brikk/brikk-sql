package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.pipeRunnableStageKeywords
import dev.brikk.house.sql.shape.*
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.desugarPipes
import kotlin.test.*

class DatafusionExecutionPolicyTest {
    @Test fun fromSubqueryPipesOwnTheOuterOrderingAndAggregatesKeepInputOrderingInside() {
        for (dialect in listOf("datafusion", "spice")) {
            val source = Dialects.forName(dialect)
            val ordered = assertIs<PipeQuery>(source.parse("FROM (SELECT 1 AS id) t |> ORDER BY id DESC").single())
            val lowered = assertIs<Select>(desugarPipes(ordered, source))
            assertNotNull(lowered.args["order"])
            val aggregate = source.parse("FROM (SELECT 1 AS id) t |> ORDER BY id |> AGGREGATE count(*) AS n").single()!!
            val output = assertIs<Select>(desugarPipes(aggregate, source))
            assertNull(output.args["order"])
            assertIs<PipeLowering.Ok>(lowerForExecution("FROM (SELECT x1 FROM (SELECT 1 AS x1) |> SELECT x1) |> SELECT x1", dialect))
        }
    }
    @Test fun onlyMeasuredBrikkStagesAreOfferedAndUnprovedNestedStagesAreRefused() {
        for (dialect in listOf("datafusion", "spice")) {
            assertEquals(DATAFUSION_PIPE_STAGES, pipeRunnableStageKeywords(dialect).toSet())
            for (sql in listOf("FROM t |> DROP x", "FROM t |> SET x=1", "FROM t |> RENAME x AS y",
                "SELECT * FROM (FROM t |> DROP x) s", "FROM t |> JOIN u ON t.id=u.id")) {
                assertIs<PipeLowering.Refused>(lowerForExecution(sql, dialect), sql)
            }
            assertIs<PipeLowering.Ok>(lowerForExecution("FROM t |> WHERE id > 1 |> SELECT id", dialect))
            assertIs<PipeLowering.Ok>(lowerForExecution("FROM t |> WHERE id = :n", dialect))
            assertIs<PipeLowering.NotPipe>(lowerForExecution("EXPLAIN ANALYZE SELECT 1", dialect))
            assertIs<PipeLowering.NotPipe>(lowerForExecution("PREPARE p AS SELECT 1", dialect))
            // Parsing native text is independent of permission to execute a Brikk lowering.
            assertTrue(Dialects.forName(dialect).parse("FROM t |> DROP x").isNotEmpty())
        }
    }
}
