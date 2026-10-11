package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.qualify
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class StarUsingOrderingResultTest {
    private data class Result(val fields: List<Pair<String, String>>, val rows: List<List<String?>>)
    private fun rows(connection: Connection, sql: String): Result = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val fields = (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it) to result.metaData.getColumnTypeName(it) }
            val data = buildList { while (result.next()) add(fields.indices.map { result.getString(it + 1) }) }
            Result(fields, data.sortedBy { it.toString() })
        }
    }
    private fun compare(connection: Connection, source: String, schema: Any?) {
        for (policy in listOf(true, false, null)) {
            val tree = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB, schema = schema, inferSchema = policy)
            val output = tree.sql("duckdb")
            assertEquals(rows(connection, source), rows(connection, output), "$source\n$output")
        }
    }

    @Test
    fun actualDuckdbStarsMatchNamesTypesValuesAndDuplicatesAcrossJoinSides() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE a(a_id INT, k INT, a_value VARCHAR)")
                statement.execute("CREATE TABLE b(k BIGINT, b_id INT, b_value VARCHAR)")
                statement.execute("CREATE TABLE c(c_id INT, k BIGINT)")
                statement.execute("INSERT INTO a VALUES (10, 1, 'a1'), (11, 3, 'a3')")
                statement.execute("INSERT INTO b VALUES (1, 20, 'b1'), (2, 21, 'b2')")
                statement.execute("INSERT INTO c VALUES (30, 2), (31, 4), (32, 3), (33, 1)")
            }
            val schema = mapOf("a" to linkedMapOf("a_id" to "INT", "k" to "INT", "a_value" to "TEXT"),
                "b" to linkedMapOf("k" to "BIGINT", "b_id" to "INT", "b_value" to "TEXT"),
                "c" to linkedMapOf("c_id" to "INT", "k" to "BIGINT"))
            for (join in listOf("JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL JOIN", "SEMI JOIN", "ANTI JOIN")) {
                compare(connection, "SELECT * FROM a $join b USING (k)", schema)
            }
            for (source in listOf(
                "SELECT a.*, b.* FROM a FULL JOIN b USING (k)", "SELECT *, * FROM a LEFT JOIN b USING (k)",
                "SELECT * FROM a FULL JOIN b USING (k) FULL JOIN c USING (k)",
                "SELECT * FROM a JOIN b USING (k) CROSS JOIN c", "SELECT 0 AS before, *, 1 AS after FROM a LEFT JOIN b USING (k)",
                "SELECT * FROM a SEMI JOIN b USING (k) JOIN c USING (k)",
                "SELECT * FROM a ANTI JOIN b USING (k) JOIN c USING (k)",
                "SELECT * FROM a, b JOIN c USING (k)",
                "SELECT * FROM a JOIN b USING (k), c JOIN (SELECT 2::BIGINT AS k, 40 AS d_id) AS d USING (k)",
                "SELECT * FROM (SELECT a_id FROM a) AS s CROSS JOIN b", "SELECT * FROM UNNEST([1, 2]) AS u(v) CROSS JOIN a",
            )) compare(connection, source, schema)
            compare(connection, "SELECT *, k AS joined_key FROM a RIGHT JOIN b USING (k)", null)
            compare(connection, "SELECT *, k AS joined_key, a.a_id FROM a RIGHT JOIN b USING (k)", mapOf("a" to schema.getValue("a")))
        }
    }

    @Test
    fun nestedConstructsKeepTheirRowsAndAllWrapperLevelJoins() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((name, field, value) in listOf(Triple("a", "av", 1), Triple("b", "bv", 2), Triple("c", "cv", 3))) {
                    statement.execute("CREATE TABLE $name($field INT)")
                    statement.execute("INSERT INTO $name VALUES ($value)")
                }
            }
            val schema = mapOf("a" to mapOf("av" to "INT"), "b" to mapOf("bv" to "INT"), "c" to mapOf("cv" to "INT"))
            for (source in listOf(
                "SELECT * FROM ((a CROSS JOIN b) CROSS JOIN c) AS j",
                "SELECT * FROM ((a CROSS JOIN b) AS i CROSS JOIN c) AS j",
                "SELECT * FROM ((SELECT av FROM a) CROSS JOIN b) AS j",
                "SELECT * FROM ((SELECT av FROM a) CROSS JOIN (b CROSS JOIN c) AS k) AS j",
            )) compare(connection, source, schema)
        }
    }
}
