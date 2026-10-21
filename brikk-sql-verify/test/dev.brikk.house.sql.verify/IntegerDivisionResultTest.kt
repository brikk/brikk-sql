package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntegerDivisionResultTest {
    @Test
    fun typedSourceIntegerDivisionTruncatesAllSignsAndRetainsNullsAndBigintResults() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((left, right, expected) in listOf(
                    Triple("7", "2", 3L), Triple("-7", "2", -3L),
                    Triple("7", "-2", -3L), Triple("-7", "-2", 3L),
                    Triple("1", "2", 0L), Triple("-1", "2", 0L),
                    Triple("6000000001", "2", 3000000000L),
                    Triple("NULL", "2", null), Triple("7", "NULL", null),
                )) {
                    val source = "SELECT CAST($left AS BIGINT) / CAST($right AS BIGINT) AS q"
                    val output = SqlFragment(source, "postgres").transpileTo("duckdb")
                    assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                    statement.executeQuery(output.sql).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getObject(1), output.sql)
                        assertEquals("BIGINT", rows.metaData.getColumnTypeName(1))
                    }
                }
            }
        }
    }

    @Test
    fun annotatedColumnsAndTargetFloatingFunctionOperandsUseTruncation() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t(x BIGINT, y BIGINT)")
                statement.execute("INSERT INTO t VALUES (7, 2), (-7, 2), (7, -2), (-7, -2), (NULL, 2), (7, NULL)")
                val tree = annotateTypes(Dialects.POSTGRES.parseOne("SELECT t.x / t.y AS q FROM t"),
                    schema = mapOf("t" to mapOf("x" to "BIGINT", "y" to "BIGINT")), dialect = Dialects.POSTGRES)
                val output = tree.sql("duckdb")
                statement.executeQuery(output).use { rows ->
                    for (expected in listOf(3L, -3L, -3L, 3L, null, null)) {
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getObject(1), output)
                    }
                }
                val function = Dialects.POSTGRES.parseOne("SELECT FLOOR(7) / FLOOR(2) AS q")
                function.findAll<Floor>().forEach { it.typeSlot = DataType.build(DType.BIGINT) }
                statement.executeQuery(function.sql("duckdb")).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(3L, rows.getObject(1))
                    assertEquals("BIGINT", rows.metaData.getColumnTypeName(1))
                }
            }
        }
    }

    @Test
    fun typedAndNativeZeroDivisorsFailWhileTryAndNullOperandsReturnNull() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val source = "SELECT CAST(7 AS BIGINT) / CAST(0 AS BIGINT)"
            val tree = Dialects.POSTGRES.parseOne(source)
            val output = tree.sql("duckdb")
            assertFailsWith<SQLException>(output) {
                connection.createStatement().use { it.executeQuery(output).close() }
            }
            tree.findAll<Div>().single().set("safe", true)
            for (sql in listOf(tree.sql("duckdb"), "SELECT NULL // 0")) {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(null, rows.getObject(1), sql)
                    }
                }
            }
            for (sql in listOf("SELECT 7 // 0", "SELECT 7 % 0")) {
                val failure = assertFailsWith<SQLException>(sql) {
                    connection.createStatement().use { it.executeQuery(sql).close() }
                }
                assertTrue("Division by zero" in failure.message.orEmpty(), failure.message)
            }
            val safeNonzero = annotateTypes(Dialects.POSTGRES.parseOne("SELECT 7 / 2"), dialect = Dialects.POSTGRES)
            safeNonzero.findAll<Div>().single().set("safe", true)
            connection.createStatement().use { statement ->
                statement.executeQuery(safeNonzero.sql("duckdb")).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(3L, rows.getObject(1))
                }
            }
        }
    }

    @Test
    fun nativeIntegerOperatorsKeepExactWideIntegerValuesAndTrinoGrammarAcceptsFallbacks() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (dialect in listOf("duckdb", "mysql", "hive", "spark", "bigquery")) {
                    val call = when (dialect) {
                        "duckdb" -> "x // y"
                        "bigquery" -> "DIV(x, y)"
                        else -> "x DIV y"
                    }
                    val source = "SELECT $call AS q FROM (SELECT CAST(9223372036854775807 AS BIGINT) AS x, CAST(1 AS BIGINT) AS y) AS t"
                    val output = SqlFragment(source, dialect).transpileTo("duckdb")
                    assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                    statement.executeQuery(output.sql).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(Long.MAX_VALUE, rows.getLong(1), output.sql)
                    }
                }
            }
        }
        for (source in listOf("SELECT x DIV y FROM t", "SELECT -7 DIV 2", "SELECT NULL DIV 2")) {
            val output = SqlFragment(source, "hive").transpileTo("trino")
            assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
            assertIs<VerifyResult.Accepted>(SqlVerifiers.forEngine("trino")!!.verify(output.sql), output.sql)
        }
        val mysql = Dialects.BIGQUERY.parseOne("SELECT DIV(10, DIV(4, 2))")
        assertIs<VerifyResult.Accepted>(SqlVerifiers.forEngine("doris")!!.verify(mysql.sql("doris")))
    }
}
