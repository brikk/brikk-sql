package dev.brikk.house.sql

import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each pipe stage reads the finished rows of the stages before it (GoogleSQL's sequential
 * semantics): lowering merges a stage into the current SELECT only where that is equivalent.
 * PipeLoweringParityTest checks the same rows on DuckDB against a staged oracle.
 */
class PipeSequentialStagesTest {
    private fun lowered(pipe: String) = SqlFragment(pipe, "duckdb").toExecutable("duckdb").sql

    @Test
    fun orderByAfterASliceSortsTheSlice() {
        assertEquals("WITH __tmp1 AS (SELECT * FROM t LIMIT 2) SELECT * FROM __tmp1 AS t ORDER BY a", lowered("FROM t |> LIMIT 2 |> ORDER BY a"))
        assertEquals("WITH __tmp1 AS (SELECT * FROM t AS e ORDER BY a OFFSET 1) SELECT * FROM __tmp1 AS e ORDER BY e.b",
            lowered("FROM t e |> ORDER BY a |> OFFSET 1 |> ORDER BY e.b"))
    }
}
