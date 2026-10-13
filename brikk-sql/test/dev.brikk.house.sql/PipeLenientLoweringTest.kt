package dev.brikk.house.sql

import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Editor scopes lower pipes as execution does. Leniency covers only what execution refuses: an input
 * boundary drops the namespaces it cannot keep, and other refusals lower as written and are reported.
 */
class PipeLenientLoweringTest {
    private val duckdb = Dialects.DUCKDB

    private fun strict(sql: String) = duckdb.generate(desugarPipes(duckdb.parseOne(sql), duckdb))

    private fun lenient(sql: String, refusals: MutableList<String> = ArrayList()) =
        duckdb.generate(desugarPipes(duckdb.parseOne(sql), duckdb, copy = true) { _, message -> refusals += message })

    @Test
    fun anUnaliasedInputLosesItsQualifierAcrossABoundary() {
        val sql = "FROM src() |> LIMIT 1 |> WHERE src.id > 0"
        assertFailsWith<UnsupportedError> { strict(sql) }
        val refusals = ArrayList<String>()
        assertEquals("WITH __tmp1 AS (SELECT * FROM SRC() LIMIT 1) SELECT * FROM __tmp1 WHERE src.id > 0", lenient(sql, refusals))
        assertEquals(emptyList(), refusals)
    }

    @Test
    fun aMultiSourceInputLosesItsQualifiersAcrossABoundary() {
        val sql = "FROM a e |> JOIN b r ON e.id = r.id |> DISTINCT |> WHERE e.id > 0"
        assertFailsWith<UnsupportedError> { strict(sql) }
        assertEquals("WITH __tmp1 AS (SELECT DISTINCT * FROM a AS e JOIN b AS r ON e.id = r.id) SELECT * FROM __tmp1 WHERE e.id > 0", lenient(sql))
    }

    @Test
    fun otherRefusalsLowerAsWrittenAndAreReported() {
        val refusals = ArrayList<String>()
        assertFailsWith<UnsupportedError> { strict("FROM a |> LIMIT :n") }
        assertEquals("SELECT * FROM a LIMIT \$n", lenient("FROM a |> LIMIT :n", refusals))
        assertFailsWith<UnsupportedError> { strict("FROM a |> RENAME a.x AS y") }
        lenient("FROM a |> RENAME a.x AS y", refusals)
        assertEquals(listOf("Pipe LIMIT/OFFSET requires a non-negative signed 64-bit integer literal",
            "Pipe RENAME requires unqualified source and target column identifiers"), refusals)
    }

    @Test
    fun whatExecutionAcceptsLowersTheSameBothWays() {
        for (sql in listOf(
            "FROM a e |> WHERE e.id > 0 |> LIMIT 2 |> WHERE e.id < 9 |> SELECT id",
            "FROM a e |> DROP x |> JOIN b r ON id = r.id |> WHERE r.code IS NULL",
            "FROM t |> SET a = 1 |> JOIN u USING (a)",
            "SELECT id AS z FROM a |> EXTEND z + 1 AS y",
            "FROM t |> LIMIT 10 |> AGGREGATE COUNT(*) AS n",
            "FROM x |> JOIN y ON x.id = y.id |> PIVOT(SUM(x1) FOR quarter IN ('foo1', 'foo2'))",
        )) assertEquals(strict(sql), lenient(sql), sql)
    }
}
