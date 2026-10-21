package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.qualify
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComprehensionQualificationResultTest {
    @Test
    fun qualifiedMethodCallsAndNestedLambdasRetainBindingSensitiveSpelling() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t(items VARCHAR[], x VARCHAR)")
                statement.execute("INSERT INTO t VALUES (['a', 'b'], 'table')")
                for ((source, expected) in listOf(
                    "SELECT [X.UPPER() FOR x IN items] AS result FROM t" to "[\"TABLE\",\"TABLE\"]",
                    "SELECT [X.UPPER() FOR X IN items] AS result FROM t" to "[\"TABLE\",\"TABLE\"]",
                    "SELECT [LIST_TRANSFORM(['c'], LAMBDA X : X.UPPER()) FOR x IN items] AS result FROM t" to "[[\"TABLE\"],[\"TABLE\"]]",
                )) {
                    val qualified = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB,
                        schema = mapOf("t" to mapOf("items" to "ARRAY<TEXT>", "x" to "TEXT")), inferSchema = false).sql("duckdb")
                    for (sql in listOf(source, qualified)) {
                        statement.executeQuery("SELECT to_json(result) FROM ($sql) AS q").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(expected, rows.getString(1), sql)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun schemalessWholeRowsAndQuotedLocalNamesStayExecutableWithoutInventedColumns() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t(items INT[], value INT)")
                statement.execute("INSERT INTO t VALUES ([1, 2], 99)")
                for (policy in listOf(true, false, null)) {
                    val record = qualify(Dialects.DUCKDB.parseOne("SELECT t AS result FROM t"), dialect = Dialects.DUCKDB,
                        inferSchema = policy).sql("duckdb")
                    statement.executeQuery("SELECT to_json(result) FROM ($record) AS q").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("{\"items\":[1,2],\"value\":99}", rows.getString(1), record)
                    }
                    val source = "SELECT [\"odd var\" FOR \"odd var\" IN items] AS result FROM t"
                    val quoted = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB,
                        schema = mapOf("t" to mapOf("items" to "ARRAY<INT>", "value" to "INT")),
                        inferSchema = policy).sql("duckdb")
                    statement.executeQuery("SELECT to_json(result) FROM ($quoted) AS q").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("[1,2]", rows.getString(1), quoted)
                    }
                }
            }
        }
    }

    @Test
    fun actualDuckdbRowsKeepLocalBindingsFreeColumnsFiltersAndNestedShadowing() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t(items INTEGER[], nested INTEGER[][], x INTEGER, i INTEGER, threshold INTEGER)")
                statement.execute("INSERT INTO t VALUES ([1, 2, -1], [[1, 2], [3]], 99, 88, 10), ([], [], 99, 88, 10), (NULL, NULL, 99, 88, 10)")
                val schema = mapOf("t" to mapOf("items" to "ARRAY<INT>", "nested" to "ARRAY<ARRAY<INT>>",
                    "x" to "INT", "i" to "INT", "threshold" to "INT"))
                for ((source, expected) in listOf(
                    "SELECT [x + threshold FOR x, i IN items IF x > 0 AND i > 1] AS result FROM t" to listOf("[12]", "[]", null),
                    "SELECT [[x FOR x IN x] FOR x IN nested] AS result FROM t" to listOf("[[1,2],[3]]", "[]", null),
                    "SELECT [[x + y FOR x IN [1, 2]] FOR y IN items] AS result FROM t" to listOf("[[2,3],[3,4],[0,1]]", "[]", null),
                    "SELECT [X FOR x IN items] AS result FROM t" to listOf("[1,2,-1]", "[]", null),
                    "SELECT [X FOR X IN items] AS result FROM t" to listOf("[1,2,-1]", "[]", null),
                    "SELECT [[X FOR X IN [1, 2]] FOR x IN items] AS result FROM t" to listOf("[[1,2],[1,2],[1,2]]", "[]", null),
                    "SELECT [[x FOR X IN [1, 2]] FOR x IN items] AS result FROM t" to listOf("[[1,2],[1,2],[1,2]]", "[]", null),
                )) {
                    for (policy in listOf(true, false, null)) {
                        val qualified = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB,
                            schema = schema, inferSchema = policy).sql("duckdb")
                        for (sql in listOf(source, qualified)) {
                            statement.executeQuery("SELECT to_json(result) FROM ($sql) AS q").use { rows ->
                                for (value in expected) {
                                    assertTrue(rows.next())
                                    assertEquals(value, rows.getString(1), sql)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun actualDuckdbTableFieldPrecedenceAndStructLocalsAreRetained() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t(items STRUCT(field INT)[], field INT)")
                statement.execute("INSERT INTO t VALUES ([{'field': 1}, {'field': 2}], 99)")
                for ((suffix, expected) in listOf("" to "[1,2]", " AS x" to "[99,99]")) {
                    val source = "SELECT [x.field FOR x IN items] AS result FROM t$suffix"
                    val qualified = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB,
                        schema = mapOf("t" to mapOf("items" to "ARRAY<STRUCT<field INT>>", "field" to "INT")),
                        inferSchema = false).sql("duckdb")
                    for (sql in listOf(source, qualified)) {
                        statement.executeQuery("SELECT to_json(result) FROM ($sql) AS q").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(expected, rows.getString(1), sql)
                        }
                    }
                }
                val unknownColumn = "SELECT [(SELECT x FROM (SELECT 9 AS x)) + x FOR x IN [1, 2]] AS result"
                val qualified = qualify(Dialects.DUCKDB.parseOne(unknownColumn), dialect = Dialects.DUCKDB,
                    inferSchema = false).sql("duckdb")
                for (sql in listOf(unknownColumn, qualified)) {
                    val error = assertFailsWith<SQLException> {
                        connection.createStatement().use { it.executeQuery(sql).close() }
                    }
                    assertTrue("subqueries in lambda" in error.message.orEmpty(), error.message)
                }
            }
        }
    }
}
