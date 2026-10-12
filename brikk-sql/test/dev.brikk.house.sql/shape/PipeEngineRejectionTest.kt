package dev.brikk.house.sql.shape

import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * brikk-extensions #55: pipe stages whose lowering the target engine rejects (Trino 483,
 * DuckDB 1.5.6, ClickHouse 26.7, Doris 4.1.4 grammar) refuse for execution; previews stay.
 */
class PipeEngineRejectionTest {

    private val refused = listOf(
        Triple("trino", "FROM t |> CALL exclude_columns(DESCRIPTOR(a))", "pipe CALL"),
        Triple("trino", "FROM t |> STRAIGHT_JOIN u ON t.a = u.a", "STRAIGHT_JOIN"),
        Triple("trino", "FROM t |> STRAIGHT_JOIN u USING (a)", "STRAIGHT_JOIN"),
        Triple("trino", "FROM t |> ASOF JOIN u ON t.a = u.a AND t.b >= u.c", "ASOF JOIN"),
        Triple("trino", "FROM t |> NATURAL JOIN u", "NATURAL JOIN"),
        Triple("duckdb", "FROM t |> STRAIGHT_JOIN u ON t.a = u.a", "STRAIGHT_JOIN"),
        Triple("duckdb", "FROM t |> STRAIGHT_JOIN u USING (a)", "STRAIGHT_JOIN"),
        Triple("doris", "FROM t |> CALL f(1)", "pipe CALL"),
        Triple("doris", "FROM t |> STRAIGHT_JOIN u ON t.a = u.a", "STRAIGHT_JOIN"),
        Triple("doris", "FROM t |> NATURAL JOIN u", "NATURAL JOIN"),
        Triple("clickhouse", "FROM t |> CALL view()", "pipe CALL"),
        Triple("clickhouse", "FROM t |> RENAME a AS z", "RENAME"),
        Triple("clickhouse", "FROM t |> SELECT a, b |> RENAME a AS z", "RENAME"),
    )

    @Test
    fun everyExecutableRouteRefuses() {
        for ((dialect, sql, construct) in refused) {
            val fragment = SqlFragment(sql, dialect)
            val original = fragment.ast.copy()
            for (route in listOf<() -> Any>(
                { fragment.toExecutable(dialect) },
                { fragment.toExecutable(dialect, pretty = true, trackSourceMap = false) },
                { fragment.toStandardSql(dialect) },
                { fragment.toSourcePreservingExecutable(dialect) },
            )) {
                val error = assertFailsWith<UnsupportedError>("$dialect: $sql") { route() }
                assertTrue(construct in error.message.orEmpty(), "$dialect: ${error.message}")
            }
            assertTrue(construct in assertIs<PipeLowering.Refused>(lowerForExecution(sql, dialect), sql).message)
            assertEquals(original, fragment.ast, sql)
        }
    }

    @Test
    fun previewsKeepTheirBestEffortOutput() {
        assertTrue("STRAIGHT_JOIN" in SqlFragment("FROM t |> STRAIGHT_JOIN u ON t.a = u.a", "trino")
            .transpileTo("trino", desugarPipes = true).sql)
        assertTrue("RENAME (a AS z)" in SqlFragment("FROM t |> RENAME a AS z", "clickhouse")
            .transpileTo("clickhouse", desugarPipes = true).sql)
        assertTrue("F((SELECT * FROM t))" in SqlFragment("FROM t |> CALL f()", "doris")
            .transpileTo("doris", desugarPipes = true).sql.replace("( ", "(").replace(" )", ")"))
    }

    @Test
    fun stagesTheEnginesAcceptStillLower() {
        for ((dialect, sql) in listOf(
            "duckdb" to "FROM t |> CALL summary()",
            "duckdb" to "FROM t |> ASOF JOIN u ON t.a = u.a AND t.b >= u.c",
            "duckdb" to "FROM t |> NATURAL JOIN u",
            "duckdb" to "FROM t |> RENAME a AS z",
            "doris" to "FROM t |> ASOF JOIN u ON t.a = u.a AND t.b >= u.c",
            "clickhouse" to "FROM t |> ASOF JOIN u ON t.a = u.a AND t.b >= u.c",
        )) {
            assertIs<PipeLowering.Ok>(lowerForExecution(sql, dialect), "$dialect: $sql")
        }
    }

    @Test
    fun clickhouseRenameExpandsFromACompleteLayout() {
        val complete = ShapeCatalog(tables = mapOf("t" to Shape.of("a" to "INT", "b" to "String", "c" to "Int64")))
        val sql = SqlFragment("FROM t |> RENAME a AS z", "clickhouse").toStandardSql("clickhouse", complete, expandStars = true)
        assertFalse("RENAME" in sql, sql)
        assertTrue("__tmp1.a AS z, __tmp1.b AS b, __tmp1.c AS c" in sql, sql)
    }
}
