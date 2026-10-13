package dev.brikk.house.sql.verify

import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pipes against a staged oracle on DuckDB: each stage materialized over the previous one, as
 * GoogleSQL's sequential semantics read them. Core's executable lowering must return the same rows
 * (a multiset, in order where the pipe ends with ORDER BY) or fail where the oracle fails, and the
 * editor's scope must agree with execution: clean with the same columns, or diagnosed where it
 * fails or is refused. The pipes include Fable's P5 round-2 parity set.
 */
class PipeLoweringParityTest {
    private val tables = mapOf(
        "t" to listOf("a" to "INTEGER", "b" to "VARCHAR"),
        "u" to listOf("a" to "INTEGER", "c" to "VARCHAR"),
        "x" to listOf("id" to "INTEGER", "x1" to "INTEGER", "quarter" to "VARCHAR", "foo1" to "INTEGER", "foo2" to "INTEGER"),
        "y" to listOf("id" to "INTEGER", "y1" to "INTEGER"),
    )
    private val rows = mapOf(
        "t" to "(3,'p'),(2,'q'),(2,'q'),(1,NULL),(NULL,'r')",
        "u" to "(1,'x'),(2,'y'),(4,'z')",
        "x" to "(1,10,'foo1',5,6),(1,20,'foo2',7,8),(2,30,'foo1',9,10)",
        "y" to "(1,100),(2,200)",
    )
    private val facts = RelationFacts(
        tables = tables.mapValues { (_, columns) ->
            RelationFact(columns.map { (name, type) -> ColumnFact(name, type, nullability = Nullability.NULLABLE, origin = ColumnOrigin.PHYSICAL_TABLE) })
        },
        catalog = CatalogKnowledge.CLOSED,
    )

    private val pipes = """
        FROM t |> DROP b |> JOIN u ON true
        FROM t |> SET a = 1 |> JOIN u USING (a)
        FROM t |> DROP b |> EXTEND 1 AS x
        FROM t |> RENAME a AS z |> JOIN u ON true
        FROM t |> LIMIT 1 |> WINDOW ROW_NUMBER() OVER () AS rn
        FROM t e |> LIMIT 1 |> JOIN u ON e.a = u.a
        FROM t |> WHERE a > 1 |> PIVOT (COUNT(*) FOR b IN ('p', 'q'))
        FROM x |> JOIN y ON x.id = y.id |> PIVOT (SUM(x1) FOR quarter IN ('foo1', 'foo2'))
        FROM x |> JOIN y ON x.id = y.id |> UNPIVOT (col FOR item IN (foo1, foo2))
        FROM t |> LIMIT 2 |> ORDER BY a
        FROM t |> ORDER BY a |> LIMIT 2 |> ORDER BY b
        FROM t |> ORDER BY a DESC |> LIMIT 2 |> WHERE a > 0
        FROM t |> LIMIT 3 |> EXTEND row_number() OVER (ORDER BY a) AS rn
        FROM t |> LIMIT 3 |> EXTEND a + 1 AS x
        FROM t |> DISTINCT |> EXTEND a + 1 AS x
        FROM t |> OFFSET 1 |> DISTINCT |> LIMIT 2
        FROM t |> ORDER BY a |> SELECT a, b |> LIMIT 2
        FROM t |> LIMIT 10 |> AGGREGATE count(*) AS n
        FROM t |> DISTINCT |> AGGREGATE count(*) AS n
        FROM t |> SELECT a |> DISTINCT |> AGGREGATE count(*) AS n
        FROM t |> DISTINCT |> SELECT a |> AGGREGATE count(*) AS n
        FROM t |> LIMIT 2 |> AGGREGATE count(*) AS n GROUP BY a
        FROM t e |> LIMIT 2 |> AGGREGATE count(*) AS n GROUP BY e.a
        FROM t |> LIMIT 2 |> AGGREGATE count(*) AS n GROUP BY t.a
        FROM t |> LIMIT 10 |> JOIN u ON t.a = u.a
        FROM t e |> LIMIT 10 |> JOIN u r ON e.a = r.a |> WHERE r.c = 'y'
        FROM t e |> WHERE e.a > 1 |> ORDER BY e.a |> LIMIT 2 |> SELECT e.a AS q
        FROM t e |> WHERE e.a > 1 |> SELECT e.a AS q |> WHERE e.a > 2
        FROM t |> SELECT a |> ORDER BY b
        FROM t |> AS v |> WHERE v.a > 1 |> SELECT v.a
        FROM t e |> DISTINCT |> WHERE e.a > 1 |> SELECT e.b
        FROM t e |> EXTEND e.a * 2 AS d |> WHERE e.a > 1
        FROM t |> WHERE t.a > 1 |> RIGHT JOIN u ON t.a = u.a
        FROM t |> WHERE a > 1 |> JOIN u ON t.a = u.a
        FROM t |> WHERE a > 1 |> JOIN u USING (a)
        FROM t |> WHERE a > 1 |> LEFT JOIN u ON t.a = u.a
        FROM t e |> WHERE e.a > 1 |> FULL JOIN u r ON e.a = r.a
        SELECT a, b FROM t |> EXTEND 1 AS x
        SELECT a AS z FROM t |> EXTEND z + 1 AS y
        SELECT a, b FROM t |> WHERE a > 1 |> EXTEND a * 2 AS d |> WHERE d > 4
        SELECT a AS z, b FROM t |> ORDER BY z
        SELECT a, count(*) AS n FROM t GROUP BY a |> WHERE n > 1 |> SELECT a
        SELECT a FROM t ORDER BY a LIMIT 3 |> WHERE a > 1
        SELECT DISTINCT a FROM t |> EXTEND a * 2 AS d
        SELECT a FROM t |> JOIN u USING (a)
        SELECT a, b FROM t |> JOIN u ON t.a = u.a
        SELECT a AS z FROM t |> AGGREGATE count(*) AS n GROUP BY z
        SELECT a FROM t |> ORDER BY b
        SELECT a FROM t |> WHERE b = 'p'
        SELECT t.a, t.b FROM t |> WHERE t.a > 1
        FROM t |> SET a = a - 1 |> JOIN u USING (a)
        FROM t |> SET b = 'z' |> WHERE b = 'z' |> SELECT a
        FROM t |> DROP b |> EXTEND 1 AS x |> SELECT x
        FROM t |> EXTEND a AS a2 |> DROP a |> SELECT a2
        FROM t |> RENAME a AS z |> WHERE z > 1 |> SELECT z, b
        FROM t |> DROP b |> JOIN u ON true |> SELECT c
        FROM t |> DROP b |> WHERE b = 'p'
        FROM t |> SET a = 1 |> AGGREGATE count(*) AS n GROUP BY a
        FROM t e |> SET a = e.a + 1 |> WHERE a > 2
        FROM t |> EXTEND a * 2 AS d |> AGGREGATE sum(d) AS s GROUP BY b
        FROM t |> WHERE a IS NOT NULL |> WINDOW row_number() OVER (ORDER BY a) AS rn |> WHERE rn <= 2
        FROM t |> AGGREGATE count(*) AS n GROUP BY a |> WHERE n > 1
        FROM t |> AGGREGATE count(*) AS n GROUP BY a |> ORDER BY n DESC, a |> LIMIT 1
        FROM t |> AGGREGATE count(*) AS n GROUP BY a |> EXTEND n * 2 AS m
        FROM t |> AGGREGATE count(*) AS n |> JOIN u ON true
        FROM t |> WHERE a > 1 |> AGGREGATE count(*) AS n GROUP BY b |> AGGREGATE sum(n) AS total
        FROM t |> WHERE a > 1 |> PIVOT (count(*) FOR b IN ('p', 'q'))
        FROM x |> JOIN y USING (id) |> PIVOT (sum(x1) FOR quarter IN ('foo1', 'foo2'))
        FROM x |> WHERE id = 1 |> UNPIVOT (col FOR item IN (foo1, foo2))
        FROM x |> LIMIT 2 |> PIVOT (sum(x1) FOR quarter IN ('foo1', 'foo2'))
        FROM x |> PIVOT (sum(x1) FOR quarter IN ('foo1', 'foo2')) |> WHERE foo1 > 10
        FROM t |> LIMIT 1 |> WHERE t.a > 0
        FROM t |> DISTINCT |> WHERE t.a > 0
        FROM t e |> DISTINCT |> EXTEND e.a + 1 AS x
        FROM t e |> LIMIT 2 |> EXTEND e.a + 1 AS x
        FROM t e |> LIMIT 2 |> WINDOW row_number() OVER (ORDER BY e.a) AS rn
        FROM t e |> LIMIT 2 |> PIVOT (count(*) FOR b IN ('p', 'q'))
        FROM t e |> LIMIT 2 |> ORDER BY e.a
        FROM t e |> EXTEND a + 1 AS x |> LIMIT 2 |> EXTEND row_number() OVER () AS rn
        FROM t e |> ORDER BY e.a |> LIMIT 2 |> AGGREGATE count(*) AS n
        FROM t |> SET a = t.a + 1 |> WHERE a > 2
        FROM t e |> DROP b |> WHERE e.a > 1
        FROM t e |> RENAME b AS bb |> WHERE e.a > 1
        FROM t e |> SET b = 'z' |> WHERE e.a > 1
        FROM t |> LIMIT 2 |> LIMIT 1 |> ORDER BY a
        FROM t |> ORDER BY a |> OFFSET 1 |> ORDER BY b
        FROM t |> WHERE a > 1 |> CROSS JOIN u
        FROM t |> WHERE a > 1 |> JOIN u ON u.a = t.a |> WHERE c = 'y'
        FROM t |> ORDER BY a |> JOIN u USING (a) |> LIMIT 1
    """.trimIndent().lines().filter(String::isNotBlank)

    /** Rows and their width; materializing a stage renames repeated column names, so names are compared with the scope only. */
    private class Result(val columns: List<String>, val rows: List<String>) {
        fun shown(ordered: Boolean) = "${columns.size} columns ${if (ordered) rows else rows.sorted()}"
    }

    @Test
    fun executionReturnsTheStagedOraclesRowsAndTheScopeAgrees() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.execute("SET threads = 1")
            for ((name, columns) in tables) {
                connection.execute("CREATE TABLE $name (${columns.joinToString { (column, type) -> "$column $type" }})")
                connection.execute("INSERT INTO $name VALUES ${rows.getValue(name)}")
            }
            val differences = pipes.mapNotNull { pipe -> difference(connection, pipe) }
            assertEquals(emptyList(), differences, differences.joinToString("\n\n"))
        }
    }

    private fun difference(connection: Connection, pipe: String): String? {
        val ordered = Regex("\\|>\\s*ORDER BY [^|]*$", RegexOption.IGNORE_CASE).containsMatchIn(pipe)
        val oracle = try {
            staged(connection, pipe).shown(ordered)
        } catch (e: SQLException) {
            null
        }
        val (lowered, execution) = try {
            val sql = SqlFragment(pipe, "duckdb").toExecutable("duckdb").sql
            sql to try {
                connection.query(sql)
            } catch (e: SQLException) {
                null
            }
        } catch (e: UnsupportedError) {
            "refused: ${e.message}" to null
        }
        val scope = RelationScope.analyze(pipe, "duckdb", facts)
        val rowsAgree = oracle == execution?.shown(ordered)
        val editorAgrees = if (execution == null) scope.diagnostics.isNotEmpty()
            else scope.diagnostics.isEmpty() && scope.output?.columns?.map { it.name } == execution.columns
        if (rowsAgree && editorAgrees) return null
        return "$pipe\n  oracle:    $oracle\n  execution: ${execution?.shown(ordered)}\n  lowered:   $lowered\n" +
            "  editor:    ${scope.output?.columns?.map { it.name }} ${scope.diagnostics.map { it.problem }}"
    }

    /**
     * Materializes each stage over the previous one, keeping a FROM-first source's range variable
     * where GoogleSQL does. JOINs extend the pending FROM clause instead, so the next stage still
     * sees the joined range variables.
     */
    private fun staged(connection: Connection, pipe: String): Result {
        val parts = pipe.split(Regex("\\s*\\|>\\s*"))
        val head = parts.first()
        var alias: String? = null
        var sql = head
        if (head.uppercase().startsWith("FROM ")) {
            val words = head.substring(5).trim().split(Regex("\\s+"))
            alias = words.getOrElse(1) { words[0] }
            sql = "SELECT * FROM ${words[0]} AS $alias"
        }
        var previous = connection.stage(sql)
        var joined: String? = null
        for (stage in parts.drop(1)) {
            val upper = stage.uppercase()
            val source = joined ?: (previous + (alias?.let { " AS $it" } ?: ""))
            if (listOf("JOIN ", "LEFT ", "RIGHT ", "FULL ", "INNER ", "CROSS ").any { upper.startsWith(it) }) {
                joined = "$source $stage"
                alias = null
                continue
            }
            joined = null
            val next = when {
                upper.startsWith("WHERE ") || upper.startsWith("ORDER BY ") || upper.startsWith("LIMIT ") || upper.startsWith("OFFSET ") ->
                    "SELECT * FROM $source $stage"
                upper == "DISTINCT" -> "SELECT DISTINCT * FROM $source"
                upper.startsWith("SELECT ") -> "$stage FROM $source".also { alias = null }
                upper.startsWith("EXTEND ") || upper.startsWith("WINDOW ") -> "SELECT *, ${stage.substring(7)} FROM $source".also { alias = null }
                upper.startsWith("AGGREGATE ") -> {
                    val body = stage.substring(10)
                    val group = body.uppercase().indexOf(" GROUP BY ")
                    val aggregates = if (group < 0) body else body.substring(0, group)
                    val keys = if (group < 0) null else body.substring(group + 10)
                    alias = null
                    "SELECT ${keys?.let { "$it, " }.orEmpty()}$aggregates FROM $source${keys?.let { " GROUP BY $it" }.orEmpty()}"
                }
                upper.startsWith("SET ") -> "SELECT * REPLACE (${stage.substring(4).split(",").joinToString { assignment ->
                    val (column, value) = assignment.split("=", limit = 2)
                    "${value.trim()} AS ${column.trim()}"
                }}) FROM $source".also { alias = null }
                upper.startsWith("DROP ") -> "SELECT * EXCLUDE (${stage.substring(5)}) FROM $source".also { alias = null }
                upper.startsWith("RENAME ") -> "SELECT * RENAME (${stage.substring(7)}) FROM $source".also { alias = null }
                upper.startsWith("PIVOT") || upper.startsWith("UNPIVOT") -> {
                    val input = if (source.startsWith(previous) && !source.contains(" JOIN ", ignoreCase = true)) previous else connection.stage("SELECT * FROM $source")
                    alias = null
                    "SELECT * FROM $input $stage"
                }
                upper.startsWith("AS ") -> "SELECT * FROM $previous".also { alias = stage.substring(3).trim() }
                else -> error("The staged oracle does not know the stage $stage")
            }
            previous = connection.stage(next)
        }
        return connection.query("SELECT * FROM ${joined ?: previous}")
    }

    private var stages = 0

    private fun Connection.stage(sql: String): String = "s${++stages}".also { execute("CREATE TEMP TABLE $it AS $sql") }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.query(sql: String): Result = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val columns = (1..result.metaData.columnCount).map(result.metaData::getColumnLabel)
            val rows = ArrayList<String>()
            while (result.next()) rows += columns.indices.joinToString(",", "(", ")") { result.getString(it + 1) ?: "NULL" }
            Result(columns, rows)
        }
    }
}
