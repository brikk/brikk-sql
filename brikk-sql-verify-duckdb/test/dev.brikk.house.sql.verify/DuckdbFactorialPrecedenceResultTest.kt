package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

/** DuckDB 2.0.0 d54cbbe3df expression.gram and test_factorial.test. */
internal class DuckdbFactorialPrecedenceResultTest {
    private fun Connection.rows(sql: String) = createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun forms(sql: String) = listOf(sql, SqlFragment(sql, "duckdb").toExecutable("duckdb").sql,
        assertIs<Expression>(Serde.load(Serde.dump(parseDuckdbNativeOne(sql)))).sql("duckdb"))
    @Test fun postfixPrecedenceCastsNullsAndHugeintResultsMatchNativeExecution() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val sql = "SELECT 2 * 3!, (2 * 3)!, -3!, 2 ^ 3!, 3! ^ 2, ~3!, 5 % 3!, 3! * 2, 3!::VARCHAR, 3::INTEGER!, NULL!, typeof(3!), factorial(30)"
            val expected = listOf(listOf("12", "720", "-6", "64.0", "36.0", "-7", "5", "12", "6", "6", null, "HUGEINT", "265252859812191058636308480000000"))
            for (form in forms(sql)) assertEquals(expected, c.rows(form), form)
        }
    }
    @Test fun negativeOverflowAndWrongOperandErrorsRemainNativeInsteadOfBeingFolded() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (sql in listOf("SELECT (-3)!", "SELECT 40!", "SELECT (2^3)!", "SELECT 3 ! !")) {
                val errors = forms(sql).map { form -> assertFailsWith<SQLException> { c.rows(form) }.message!!.substringBefore(':') }
                assertEquals(1, errors.toSet().size, sql)
            }
        }
    }
    @Test fun jdbcBindingsAndVolatileOperandsAreEvaluatedOnceInAuthoredOrder() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val sql = "SELECT CAST(? AS INTEGER)! + CAST(? AS INTEGER)!"
            c.prepareStatement(if (generated) SqlFragment(sql, "duckdb").toExecutable("duckdb").sql else sql).use { p ->
                assertEquals(2, p.parameterMetaData.parameterCount)
                p.setInt(1, 3)
                p.setInt(2, 4)
                p.executeQuery().use { r ->
                    assertTrue(r.next())
                    assertEquals("30", r.getString(1))
                }
            }
            c.createStatement().use { it.execute("CREATE SEQUENCE factorial_seq START 3") }
            val volatile = "SELECT CAST(nextval('factorial_seq') AS INTEGER)! - CAST(nextval('factorial_seq') AS INTEGER)!"
            assertEquals(listOf(listOf("-18")), c.rows(if (generated) SqlFragment(volatile, "duckdb").toExecutable("duckdb").sql else volatile))
            assertEquals(listOf(listOf("4")), c.rows("SELECT currval('factorial_seq')"))
        }
    }
}
