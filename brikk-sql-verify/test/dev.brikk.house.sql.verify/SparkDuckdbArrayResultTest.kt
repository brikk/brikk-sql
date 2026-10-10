package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SparkDuckdbArrayResultTest {
    @Test
    fun trinoNativeGrammarAcceptsFlattenedArrayAndScalarArguments() {
        for (call in listOf(
            "CONCAT_WS('-', ARRAY('a', NULL, 'b'), 'x')",
            "CONCAT_WS('-', ARRAY(), CAST(NULL AS ARRAY<STRING>), NULL, 'y')",
        )) {
            val output = SqlFragment("SELECT $call", "spark").transpileTo("trino")
            assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
            assertTrue(SqlVerifiers.forEngine("trino")!!.verify(output.sql).accepted, output.sql)
        }
    }

    @Test
    fun invalidSparkCountsDoNotTurnIntoSuccessfulDuckdbResults() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for (call in listOf("SLICE(ARRAY(1, 2, 3), 0, 1)", "SLICE(ARRAY(1, 2, 3), 1, -1)")) {
                val output = SqlFragment("SELECT $call", "spark").transpileTo("duckdb").sql
                val error = assertFailsWith<SQLException>(output) {
                    connection.createStatement().use { it.executeQuery(output).close() }
                }
                assertTrue("Spark SLICE" in error.message.orEmpty(), error.message)
            }
            val dynamic = SqlFragment("SELECT SLICE(a, s, n) FROM " +
                "(VALUES (ARRAY(1, 2, 3), 0, 1)) AS t(a, s, n)", "spark").transpileTo("duckdb").sql
            assertFailsWith<SQLException>(dynamic) {
                connection.createStatement().use { it.executeQuery(dynamic).close() }
            }
            val nulls = SqlFragment("SELECT SLICE(NULL, 0, -1)", "spark").transpileTo("duckdb").sql
            connection.createStatement().use { statement ->
                statement.executeQuery(nulls).use { result ->
                    assertTrue(result.next())
                    assertEquals(null, result.getObject(1))
                }
            }
        }
    }

    @Test
    fun slicesExecuteWithLengthNegativeStartZeroLengthAndNullSemantics() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((call, expected) in listOf(
                    "SLICE(ARRAY(1, 2, 3), 1, 2)" to "[1,2]",
                    "SLICE(ARRAY(1, 2, 3), 2, 4)" to "[2,3]",
                    "SLICE(ARRAY(1, 2, 3), -2, 2)" to "[2,3]",
                    "SLICE(ARRAY(1, 2, 3), -4, 2)" to "[]",
                    "SLICE(ARRAY(1, 2, 3), 3, 0)" to "[]",
                    "SLICE(ARRAY(1, 2, 3), 1, 0)" to "[]",
                    "SLICE(ARRAY(1, 2, 3), -1, 0)" to "[]",
                    "SLICE(ARRAY(1, 2, 3), 4, 2)" to "[]",
                    "SLICE(ARRAY(1, 2, 3), 2, 2147483647)" to "[2,3]",
                    "SLICE(ARRAY(), -1, 2)" to "[]",
                    "SLICE(NULL, 2, 1)" to null,
                )) {
                    for (sourceDialect in listOf("spark2", "spark")) {
                        val output = SqlFragment("SELECT $call AS a", sourceDialect).transpileTo("duckdb")
                        assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                        assertTrue(SqlVerifiers.forEngine("duckdb")!!.verify(output.sql).accepted, output.sql)
                        statement.executeQuery("SELECT to_json(a) FROM (${output.sql}) AS q").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(expected, rows.getString(1), output.sql)
                            assertFalse(rows.next())
                        }
                    }
                }
                val dynamic = SqlFragment("SELECT SLICE(a, s, n) AS result FROM " +
                    "(VALUES (ARRAY(1, 2, 3), -2, 2), (ARRAY(1, 2, 3), -4, 2), " +
                    "(ARRAY(1, 2, 3), 2, NULL), (ARRAY(1, 2, 3), NULL, 2)) AS t(a, s, n)", "spark")
                    .transpileTo("duckdb").sql
                statement.executeQuery("SELECT to_json(result) FROM ($dynamic) AS q").use { rows ->
                    for (expected in listOf("[2,3]", "[]", null, null)) {
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getString(1), dynamic)
                    }
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun concatWsExecutesFlatteningEmptyArraysMixedScalarsAndNullSkipping() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((call, expected) in listOf(
                    "CONCAT_WS('', ARRAY('a', 'b'))" to "ab",
                    "CONCAT_WS('-', ARRAY('a', NULL, '', 'b'))" to "a--b",
                    "CONCAT_WS('-', ARRAY())" to "",
                    "CONCAT_WS('-', ARRAY(NULL))" to "",
                    "CONCAT_WS(NULL, ARRAY())" to null,
                    "CONCAT_WS('-', ARRAY(1, 2), 'x')" to "1-2-x",
                    "CONCAT_WS('-', ARRAY('a', 'b'), ARRAY('c'), 'd')" to "a-b-c-d",
                    "CONCAT_WS('-', CAST(NULL AS ARRAY<STRING>))" to "",
                    "CONCAT_WS('-', 'x', ARRAY(), ARRAY(NULL), CAST(NULL AS ARRAY<STRING>), NULL, 'y')" to "x-y",
                    "CONCAT_WS('-', 1, NULL, 'a')" to "1-a",
                )) {
                    val output = SqlFragment("SELECT $call AS result", "spark").transpileTo("duckdb")
                    assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
                    assertTrue(SqlVerifiers.forEngine("duckdb")!!.verify(output.sql).accepted, output.sql)
                    statement.executeQuery(output.sql).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getString("result"), output.sql)
                        assertFalse(rows.next())
                    }
                }
                val native = SqlFragment("SELECT CONCAT_WS('-', ['a', NULL, 'b'])", "duckdb").transpileTo("duckdb").sql
                statement.executeQuery(native).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("[a, NULL, b]", rows.getString(1))
                }
            }
        }
    }

    @Test
    fun annotatedArrayAndScalarColumnsRetainPerRowNullSemantics() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t(a VARCHAR[], b VARCHAR)")
                statement.execute("INSERT INTO t VALUES (['a', NULL, 'b'], 'x'), (NULL, 'y'), ([], NULL)")
                val source = Dialects.SPARK.parseOne("SELECT CONCAT_WS('-', t.a, t.b) AS result FROM t")
                val annotated = annotateTypes(source, schema = mapOf("t" to mapOf("a" to "ARRAY<TEXT>", "b" to "TEXT")),
                    dialect = Dialects.SPARK)
                val generator = Dialects.DUCKDB.generator(sourceDialect = "spark")
                val output = generator.generate(annotated)
                assertTrue(generator.unsupportedMessages.isEmpty(), generator.unsupportedMessages.toString())
                statement.executeQuery(output).use { rows ->
                    for (expected in listOf("a-b-x", "y", "")) {
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getString(1), output)
                    }
                    assertFalse(rows.next())
                }
            }
        }
    }
}
