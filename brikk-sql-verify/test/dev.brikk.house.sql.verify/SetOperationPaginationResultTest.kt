package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Union
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetOperationPaginationResultTest {
    @Test
    fun mysqlFullJoinRewriteKeepsPaginationOnTheWholeJoinedResult() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE x(id INT)")
                statement.execute("CREATE TABLE y(id INT)")
                statement.execute("INSERT INTO x VALUES (1), (2)")
                statement.execute("INSERT INTO y VALUES (2), (3)")
                for ((suffix, expected) in listOf(
                    "ORDER BY 1 NULLS FIRST LIMIT 0" to emptyList<Int>(),
                    "ORDER BY 1 NULLS FIRST LIMIT 1 OFFSET 1" to listOf(2),
                    "ORDER BY 1 NULLS FIRST OFFSET 1" to listOf(2, 3),
                )) {
                    val source = "SELECT COALESCE(x.id, y.id) AS id FROM x FULL OUTER JOIN y ON x.id = y.id $suffix"
                    val output = SqlFragment(source, "postgres").transpileTo("mysql")
                    assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                    fun result(sql: String): List<Int> = statement.executeQuery(sql).use { rows ->
                        buildList { while (rows.next()) add(rows.getInt(1)) }
                    }
                    assertEquals(expected, result(source))
                    assertEquals(expected, result(output.sql), output.sql)
                }
            }
        }
    }

    private fun rows(sql: String): List<Int> = DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result -> buildList { while (result.next()) add(result.getInt(1)) } }
        }
    }

    @Test
    fun branchRestrictionsAndNestedSetRestrictionsExecuteIndependently() {
        val left = Dialects.DUCKDB.parseOne("SELECT x FROM (VALUES (3), (1), (2)) AS t(x) ORDER BY x LIMIT 1")
        val right = Dialects.DUCKDB.parseOne("SELECT x FROM (VALUES (6), (4), (5)) AS t(x) ORDER BY x LIMIT 1 OFFSET 1")
        val tree = Union(args("this" to left, "expression" to right, "distinct" to false))
        val output = tree.sql("duckdb")
        assertEquals(listOf(1, 5), rows(output), output)
        for (dialect in listOf("duckdb", "doris", "trino")) {
            val sql = tree.sql(dialect)
            assertTrue(SqlVerifiers.forEngine(dialect)!!.verify(sql).accepted, sql)
        }
        val nested = Dialects.DUCKDB.parseOne("SELECT 1 AS x UNION ALL SELECT 2 AS x ORDER BY x LIMIT 1")
        val union = Union(args("this" to nested, "expression" to Dialects.DUCKDB.parseOne("SELECT 3 AS x"), "distinct" to false))
        assertEquals(listOf(1, 3), rows(union.sql("duckdb")))
    }

    @Test
    fun clickhouseWrapperExecutesOuterPaginationAndKeepsCteVisibility() {
        for ((source, expected) in listOf(
            "SELECT 1 AS x UNION ALL SELECT 2 AS x ORDER BY x LIMIT 1 OFFSET 1" to listOf(2),
            "SELECT 1 AS x UNION ALL SELECT 2 AS x OFFSET 1" to listOf(2),
            "WITH q AS (SELECT 1 AS x UNION ALL SELECT 2 AS x) SELECT x FROM q UNION ALL SELECT 3 AS x ORDER BY x LIMIT 1 OFFSET 1" to listOf(2),
            "WITH q AS (SELECT 1 AS x UNION ALL SELECT 2 AS x) SELECT x FROM q UNION ALL SELECT 3 AS x ORDER BY x LIMIT (SELECT COUNT(*) - 1 FROM q)" to listOf(1),
        )) {
            val result = SqlFragment(source, "postgres").transpileTo("clickhouse")
            assertTrue(result.unsupportedMessages.isEmpty(), result.toString())
            assertEquals(expected, rows(result.sql), result.sql)
        }
    }
}
