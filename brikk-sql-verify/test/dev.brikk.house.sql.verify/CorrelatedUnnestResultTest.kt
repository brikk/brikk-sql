package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.qualify
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class CorrelatedUnnestResultTest {
    private data class Result(val fields: List<Pair<String, String>>, val rows: List<List<String?>>)
    private fun result(connection: Connection, sql: String): Result = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            val fields = (1..rows.metaData.columnCount).map { rows.metaData.getColumnLabel(it) to rows.metaData.getColumnTypeName(it) }
            val data = buildList { while (rows.next()) add(fields.indices.map { rows.getString(it + 1) }) }
            Result(fields, data.sortedBy { it.toString() })
        }
    }
    private fun compare(connection: Connection, source: String, schema: Any? = null) {
        for (policy in listOf(true, false, null)) {
            val tree = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB,
                schema = schema, inferSchema = policy)
            annotateTypes(tree, schema = schema, dialect = Dialects.DUCKDB)
            val output = tree.sql("duckdb")
            assertEquals(result(connection, source), result(connection, output), "$source\n$output")
        }
    }

    @Test
    fun nativeCorrelatedStructArraysKeepComputedTypesAliasesShadowingAndNullRows() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for (items in listOf("[{'email':'a','magnitude':2::DOUBLE}]",
                "[{'email':NULL::VARCHAR,'magnitude':NULL::DOUBLE}]",
                "[]::STRUCT(email VARCHAR, magnitude DOUBLE)[]",
                "NULL::STRUCT(email VARCHAR, magnitude DOUBLE)[]")) {
                compare(connection, "WITH t AS (SELECT $items AS items) " +
                    "SELECT (SELECT e.item.email FROM UNNEST(items) AS e(item)) AS email, " +
                    "(SELECT MAX(e.item.magnitude) FROM UNNEST(items) AS e(item)) AS magnitude FROM t")
            }
            compare(connection, "WITH t AS (SELECT [{'email':'outer'}] AS items) SELECT " +
                "(SELECT (SELECT e.item.email FROM UNNEST(items) AS e(item)) " +
                "FROM (SELECT [{'email':7::BIGINT}] AS items) AS t) AS email FROM t")
            compare(connection, "WITH t AS (SELECT [{'segments':[{'magnitude':2::DOUBLE}]}] AS legs) " +
                "SELECT (SELECT MAX(e.item.segments[1].magnitude) FROM UNNEST(legs) AS e(item)) AS magnitude FROM t")
            val schema = mapOf("base" to mapOf("items" to "INT"))
            connection.createStatement().use { it.execute("CREATE TABLE base(items INT); INSERT INTO base VALUES (9)") }
            compare(connection, "WITH t AS (SELECT [{'email':'computed'}] AS items FROM base) " +
                "SELECT (SELECT e.item.email FROM UNNEST(items) AS e(item)) AS email FROM t", schema)
        }
    }

    @Test
    fun portableBigqueryCorrelatedProjectionsExecuteInDuckdbWithoutClaimingLiveBigquery() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for ((items, expected) in listOf(
                "[STRUCT('a' AS email, CAST(2 AS FLOAT64) AS magnitude)]" to listOf("a", "2.0"),
                "[STRUCT(CAST(NULL AS STRING) AS email, CAST(NULL AS FLOAT64) AS magnitude)]" to listOf(null, null),
                "CAST([] AS ARRAY<STRUCT<email STRING, magnitude FLOAT64>>)" to listOf(null, null),
                "CAST(NULL AS ARRAY<STRUCT<email STRING, magnitude FLOAT64>>)" to listOf(null, null),
            )) {
                val source = "WITH t AS (SELECT $items AS items) " +
                    "SELECT (SELECT e.email FROM UNNEST(items) AS e) AS email, " +
                    "(SELECT MAX(e.magnitude) FROM UNNEST(items) AS e) AS magnitude FROM t"
                for (policy in listOf(true, false, null)) {
                    val tree = qualify(Dialects.BIGQUERY.parseOne(source), dialect = Dialects.BIGQUERY, inferSchema = policy)
                    annotateTypes(tree, dialect = Dialects.BIGQUERY)
                    assertEquals(listOf("STRING", "FLOAT64"), tree.selects.map { it.type?.sql("bigquery") })
                    val output = tree.sql("duckdb")
                    assertEquals(listOf(expected), result(connection, output).rows, output)
                }
            }
        }
    }

    @Test
    fun nativeTvfAndCtePivotQualificationPreserveActualFieldsTypesAndRows() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            compare(connection, "WITH sales AS (SELECT * FROM (VALUES (1,'q1',2),(1,'q2',3),(2,'q1',4)) AS v(id,quarter,amount)) " +
                "SELECT id, q1, q2 FROM sales PIVOT(SUM(amount) FOR quarter IN ('q1' AS q1,'q2' AS q2)) AS p")
            val file = File.createTempFile("correlated-unnest-tvf-", ".parquet",
                File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
            try {
                val path = file.absolutePath.replace("'", "''")
                connection.createStatement().use { it.execute("COPY (SELECT 1 AS x, 'a' AS label) TO '$path' (FORMAT PARQUET)") }
                compare(connection, "SELECT t.x, t.label FROM READ_PARQUET('$path') AS t")
            } finally { file.delete() }
        }
    }
}
