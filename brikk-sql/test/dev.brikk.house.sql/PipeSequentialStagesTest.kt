package dev.brikk.house.sql

import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.ScopeProblem
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Each pipe stage reads the finished rows of the stages before it (GoogleSQL's sequential
 * semantics): lowering merges a stage into the current SELECT only where that is equivalent.
 * PipeLoweringParityTest checks the same rows on DuckDB against a staged oracle.
 */
class PipeSequentialStagesTest {
    private fun lowered(pipe: String) = SqlFragment(pipe, "duckdb").toExecutable("duckdb").sql

    private val facts = RelationFacts(
        slots = mapOf("src" to RelationFact(listOf(ColumnFact("a")))),
        tables = mapOf("t" to RelationFact(listOf(ColumnFact("a"), ColumnFact("b"))), "u" to RelationFact(listOf(ColumnFact("a"), ColumnFact("c"))),
            "y" to RelationFact(listOf(ColumnFact("id")))),
    )

    private fun problems(pipe: String) = RelationScope.analyze(pipe, "duckdb", facts).diagnostics.map { it.problem }

    @Test
    fun orderByAfterASliceSortsTheSlice() {
        assertEquals("WITH __tmp1 AS (SELECT * FROM t LIMIT 2) SELECT * FROM __tmp1 AS t ORDER BY a", lowered("FROM t |> LIMIT 2 |> ORDER BY a"))
        assertEquals("WITH __tmp1 AS (SELECT * FROM t AS e ORDER BY a OFFSET 1) SELECT * FROM __tmp1 AS e ORDER BY e.b",
            lowered("FROM t e |> ORDER BY a |> OFFSET 1 |> ORDER BY e.b"))
    }

    @Test
    fun aWholeRowBoundaryKeepsASingleSourcesNameAsWhereDoes() {
        assertEquals("WITH __tmp1 AS (SELECT * FROM t LIMIT 2), __tmp2 AS (SELECT t.a, COUNT(*) AS n FROM __tmp1 AS t GROUP BY t.a) SELECT * FROM __tmp2",
            lowered("FROM t |> LIMIT 2 |> AGGREGATE count(*) AS n GROUP BY t.a"))
        assertEquals("WITH __tmp1 AS (SELECT * FROM t LIMIT 10) SELECT * FROM __tmp1 AS t JOIN u ON t.a = u.a",
            lowered("FROM t |> LIMIT 10 |> JOIN u ON t.a = u.a"))
        assertEquals(emptyList(), problems("FROM t |> LIMIT 2 |> AGGREGATE count(*) AS n GROUP BY t.a"))
        assertEquals(emptyList(), problems("FROM t |> LIMIT 10 |> JOIN u ON t.a = u.a"))
    }

    @Test
    fun aQualifierTheBoundaryCannotKeepIsRefusedAndUnresolved() {
        for (pipe in listOf(
            "FROM t |> JOIN u ON true |> LIMIT 1 |> JOIN y ON t.a = y.id",
            "FROM src() |> LIMIT 1 |> AGGREGATE count(*) AS n GROUP BY src.a",
        )) {
            assertFailsWith<UnsupportedError>(pipe) { lowered(pipe) }
            assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), problems(pipe), pipe)
        }
        assertEquals("WITH __tmp1 AS (SELECT * FROM t JOIN u ON TRUE LIMIT 1) SELECT * FROM __tmp1 JOIN y ON TRUE",
            lowered("FROM t |> JOIN u ON true |> LIMIT 1 |> JOIN y ON true"))
    }

    @Test
    fun aJoinAfterWhereJoinsTheFilteredRows() {
        assertEquals("WITH __tmp1 AS (SELECT * FROM t AS e WHERE e.a > 1) SELECT * FROM __tmp1 AS e FULL JOIN u AS r ON e.a = r.a",
            lowered("FROM t e |> WHERE e.a > 1 |> FULL JOIN u r ON e.a = r.a"))
        // Merged, the unqualified a would meet u's a.
        assertEquals(emptyList(), problems("FROM t |> WHERE a > 1 |> JOIN u ON t.a = u.a"))
    }

    @Test
    fun aWindowInExtendReadsTheSlice() {
        assertEquals("WITH __tmp1 AS (SELECT * FROM t LIMIT 3), __tmp2 AS (SELECT *, ROW_NUMBER() OVER (ORDER BY a) AS rn FROM __tmp1 AS t) SELECT * FROM __tmp2",
            lowered("FROM t |> LIMIT 3 |> EXTEND row_number() OVER (ORDER BY a) AS rn"))
        assertEquals("WITH __tmp1 AS (SELECT *, a + 1 AS x FROM t LIMIT 3) SELECT * FROM __tmp1", lowered("FROM t |> LIMIT 3 |> EXTEND a + 1 AS x"))
    }

    @Test
    fun setValuesReadTheInputByItsName() {
        assertEquals("WITH __tmp1 AS (SELECT * FROM t AS e), __tmp2 AS (SELECT * REPLACE (e.a + 1 AS a) FROM __tmp1 AS e) SELECT * FROM __tmp2 WHERE a > 2",
            lowered("FROM t e |> SET a = e.a + 1 |> WHERE a > 2"))
        assertEquals(emptyList(), problems("FROM t |> SET a = t.a + 1 |> WHERE a > 2"))
        // The SET output is a new row: the old name is gone after it.
        assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), problems("FROM t e |> SET b = 'z' |> WHERE e.a > 1"))
    }
}
