package dev.brikk.house.sql.verify

import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's relation scope and execution agree: each pipe lowers to SQL DuckDB runs, and the
 * columns DuckDB returns are the scope's output, in order. The cases are the stages that used to
 * rewrite a head projection, a modified star or a limited input in place.
 */
class PipeLoweringParityTest {
    private val tables = mapOf(
        "t" to listOf("a" to "INTEGER", "b" to "VARCHAR"),
        "u" to listOf("a" to "INTEGER", "c" to "VARCHAR"),
        "x" to listOf("id" to "INTEGER", "x1" to "INTEGER", "quarter" to "VARCHAR", "foo1" to "INTEGER", "foo2" to "INTEGER"),
        "y" to listOf("id" to "INTEGER", "y1" to "INTEGER"),
    )
    private val facts = RelationFacts(tables = tables.mapValues { (_, columns) ->
        RelationFact(columns.map { (name, type) -> ColumnFact(name, type, nullability = Nullability.NULLABLE, origin = ColumnOrigin.PHYSICAL_TABLE) })
    })

    private val pipes = listOf(
        "FROM t |> DROP b |> JOIN u ON true",
        "FROM t |> SET a = 1 |> JOIN u USING (a)",
        "FROM t |> DROP b |> EXTEND 1 AS x",
        "FROM t |> RENAME a AS z |> JOIN u ON true",
        "SELECT a AS z FROM t |> EXTEND z + 1 AS y",
        "SELECT a, b FROM t |> EXTEND 1 AS x",
        "SELECT a FROM t |> JOIN u USING (a)",
        "SELECT a AS z FROM t |> AGGREGATE COUNT(*) AS n GROUP BY z",
        "SELECT a, b FROM t |> WHERE a > 1",
        "SELECT a AS z, b FROM t |> ORDER BY z",
        "FROM t |> LIMIT 10 |> AGGREGATE COUNT(*) AS n",
        "FROM t |> LIMIT 1 |> WINDOW ROW_NUMBER() OVER () AS rn",
        "FROM t e |> LIMIT 1 |> JOIN u ON e.a = u.a",
        "FROM t |> DISTINCT |> AGGREGATE COUNT(*) AS n",
        "FROM t |> WHERE a > 1 |> PIVOT (COUNT(*) FOR b IN ('p', 'q'))",
        "FROM x |> JOIN y ON x.id = y.id |> PIVOT (SUM(x1) FOR quarter IN ('foo1', 'foo2'))",
        "FROM x |> JOIN y ON x.id = y.id |> UNPIVOT (col FOR item IN (foo1, foo2))",
    )

    @Test
    fun executionReturnsTheColumnsTheScopeDescribes() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for ((name, columns) in tables) {
                connection.execute("CREATE TABLE $name (${columns.joinToString { (column, type) -> "$column $type" }})")
            }
            for (pipe in pipes) {
                val scope = RelationScope.analyze(pipe, "duckdb", facts)
                assertTrue(scope.diagnostics.isEmpty(), "$pipe: ${scope.diagnostics}")
                val executable = SqlFragment(pipe, "duckdb").toExecutable("duckdb").sql
                assertEquals(connection.columns(executable), scope.output?.columns?.map { it.name }, "$pipe\n$executable")
            }
        }
    }

    @Test
    fun executionRejectsWhatTheScopeReports() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for ((name, columns) in tables) {
                connection.execute("CREATE TABLE $name (${columns.joinToString { (column, type) -> "$column $type" }})")
            }
            for (pipe in listOf("SELECT a FROM t |> ORDER BY b", "SELECT a FROM t |> WHERE b = 'p'", "FROM t |> DROP b |> WHERE b = 'p'")) {
                assertTrue(RelationScope.analyze(pipe, "duckdb", facts).diagnostics.isNotEmpty(), pipe)
                val executable = SqlFragment(pipe, "duckdb").toExecutable("duckdb").sql
                assertTrue(runCatching { connection.columns(executable) }.isFailure, "$pipe runs as $executable")
            }
        }
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.columns(sql: String): List<String> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> (1..rows.metaData.columnCount).map(rows.metaData::getColumnLabel) }
    }
}
