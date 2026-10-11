package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArraySubqueryResultTest {
    @Test
    fun emittedDuckdbRowsRetainLaterElementsAndScalarNullVersusEmptyRowArraySemantics() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((source, expected) in listOf(
                    "SELECT [(SELECT 1), 2] AS result" to "[1,2]",
                    "SELECT [1, (SELECT 2)] AS result" to "[1,2]",
                    "SELECT [(SELECT 1), (SELECT 2), 3] AS result" to "[1,2,3]",
                    "SELECT [COALESCE((SELECT 1), 0), 2] AS result" to "[1,2]",
                    "SELECT [(SELECT 1 WHERE FALSE), 2] AS result" to "[null,2]",
                    "SELECT [(SELECT 1 WHERE FALSE)] AS result" to "[null]",
                    "SELECT ARRAY(SELECT 1 WHERE FALSE) AS result" to "[]",
                    "SELECT ARRAY((SELECT 1 WHERE FALSE)) AS result" to "[]",
                    "SELECT ARRAY(SELECT 1 UNION ALL SELECT 2 ORDER BY 1) AS result" to "[1,2]",
                )) {
                    for (read in listOf("duckdb", "bigquery")) {
                        for (target in listOf("duckdb", "bigquery")) {
                            val output = SqlFragment(source, read).transpileTo(target)
                            assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                            statement.executeQuery("SELECT to_json(result) FROM (${output.sql}) AS q").use { rows ->
                                assertTrue(rows.next())
                                assertEquals(expected, rows.getString(1), "$read->$target\n${output.sql}")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun listValueAndNestedStructArraysExecuteWithoutDroppingValues() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((source, dialect, expected) in listOf(
                    Triple("SELECT LIST_VALUE((SELECT 1), 2) AS result", "duckdb", "[1,2]"),
                    Triple("SELECT LIST_VALUE((SELECT 1 WHERE FALSE)) AS result", "duckdb", "[null]"),
                    Triple("SELECT [[(SELECT 1), 2]] AS result", "duckdb", "[[1,2]]"),
                    Triple("SELECT [STRUCT((SELECT 1) AS a), STRUCT(2 AS a)] AS result", "bigquery", "[{\"a\":1},{\"a\":2}]"),
                )) {
                    val output = SqlFragment(source, dialect).transpileTo("duckdb")
                    assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                    statement.executeQuery("SELECT to_json(result) FROM (${output.sql}) AS q").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getString(1), output.sql)
                    }
                }
            }
        }
    }
}
