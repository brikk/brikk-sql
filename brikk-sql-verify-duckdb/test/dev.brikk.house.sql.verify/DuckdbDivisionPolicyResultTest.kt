package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

/** Pinned 2.0.0 d54cbbe3df numeric division/modulo, deprecated_settings and optimizer zero tests. */
internal class DuckdbDivisionPolicyResultTest {
    private fun forms(sql: String) = listOf(sql, SqlFragment(sql, "duckdb").toExecutable("duckdb").sql,
        assertIs<Expression>(Serde.load(Serde.dump(parseDuckdbNativeOne(sql)))).sql("duckdb"))
    private fun Connection.rows(sql: String) = createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun Connection.mode(value: Boolean) = createStatement().use { it.execute("SET error_on_division_by_zero=$value") }
    @Test fun ordinaryDivisionReturnsInfinityOrNanInBothModesWhileNullOperandsStayNull() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            assertEquals(listOf(listOf("true")), c.rows("SELECT current_setting('error_on_division_by_zero')"))
            for (mode in listOf(true, false)) {
                c.mode(mode)
                for (sql in forms("SELECT 1 / 0,-1.0 / 0.0,0.0 / 0.0,NULL / 0,1 / NULL,TRY(1 / 0)"))
                    c.createStatement().use { s -> s.executeQuery(sql).use { r ->
                        assertTrue(r.next())
                        assertEquals(Double.POSITIVE_INFINITY, r.getDouble(1))
                        assertEquals(Double.NEGATIVE_INFINITY, r.getDouble(2))
                        assertTrue(r.getDouble(3).isNaN())
                        assertNull(r.getObject(4))
                        assertNull(r.getObject(5))
                        assertEquals(Double.POSITIVE_INFINITY, r.getDouble(6))
                        assertEquals("DOUBLE", r.metaData.getColumnTypeName(1))
                    } }
                for (sql in forms("SELECT CAST(1 AS FLOAT) % CAST(0 AS FLOAT),CAST(1 AS DOUBLE) % CAST(0 AS DOUBLE)"))
                    c.createStatement().use { s -> s.executeQuery(sql).use { r ->
                        assertTrue(r.next())
                        assertTrue(r.getFloat(1).isNaN())
                        assertTrue(r.getDouble(2).isNaN())
                        assertEquals("FLOAT", r.metaData.getColumnTypeName(1))
                        assertEquals("DOUBLE", r.metaData.getColumnTypeName(2))
                    } }
            }
        }
    }
    @Test fun integerAndModuloZeroErrorsBecomeNullOnlyUnderExplicitLegacyPolicyOrAuthoredTry() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (mode in listOf(true, false)) {
                c.mode(mode)
                for (body in listOf("7 // 0", "7 % 0", "7.0 % 0.0", "CAST(7 AS FLOAT) // CAST(0 AS FLOAT)", "CAST(7 AS DOUBLE) // CAST(0 AS DOUBLE)")) for (sql in forms("SELECT $body")) {
                    if (mode) assertTrue(assertFailsWith<SQLException> { c.rows(sql) }.message!!.contains("Division by zero"))
                    else assertEquals(listOf(listOf<String?>(null)), c.rows(sql))
                }
                for (sql in forms("SELECT TRY(7 // 0),TRY(7 % 0),NULL // 0,7 // NULL,NULL % 0"))
                    assertEquals(listOf(listOf(null, null, null, null, null)), c.rows(sql))
                assertEquals(listOf(listOf(mode.toString())), c.rows("SELECT current_setting('error_on_division_by_zero')"))
            }
            c.createStatement().use { it.execute("RESET error_on_division_by_zero") }
            assertFailsWith<SQLException> { c.rows("SELECT 1 // 0") }
        }
    }
    @Test fun typedIntegerFloatDecimalAndWideOperandsKeepNativeResultTypesAndExactValues() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val sql = "SELECT -7 // 2,7 // -2,-7 % 2,CAST(9223372036854775807 AS BIGINT) // CAST(1 AS BIGINT),CAST(5 AS FLOAT) // CAST(2 AS FLOAT),CAST(5 AS DECIMAL(8,2)) / CAST(2 AS DECIMAL(8,2)),CAST(5 AS DECIMAL(8,2)) % CAST(2 AS DECIMAL(8,2))"
            for (form in forms(sql)) c.createStatement().use { s -> s.executeQuery(form).use { r ->
                assertEquals(listOf("INTEGER", "INTEGER", "INTEGER", "BIGINT", "FLOAT", "DOUBLE", "DECIMAL(8,2)"), (1..7).map { r.metaData.getColumnTypeName(it) })
                assertTrue(r.next())
                    assertEquals(listOf("-3", "-3", "-1", "9223372036854775807", "2.5", "2.5", "1.00"), (1..7).map {
                        r.getString(it)
                    })
            } }
            for (form in forms("SELECT typeof(NULL / NULL),typeof(NULL // NULL),typeof(NULL % NULL)"))
                assertEquals(listOf(listOf("DOUBLE", "BIGINT", "BIGINT")), c.rows(form))
        }
    }
    @Test fun bindingsAndVolatileOperandsAreNotDuplicatedReorderedOrRetried() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (mode in listOf(true, false)) {
                c.mode(mode)
                val sql = "SELECT CAST(? AS INTEGER) // CAST(? AS INTEGER),CAST(? AS INTEGER) % CAST(? AS INTEGER),? / ?"
                c.prepareStatement(if (generated) SqlFragment(sql, "duckdb").toExecutable("duckdb").sql else sql).use { p ->
                    assertEquals(6, p.parameterMetaData.parameterCount)
                listOf(7, 2, 7, 2, 7, 2).forEachIndexed { index, value -> p.setInt(index + 1, value) }
                    p.executeQuery().use { r ->
                        assertTrue(r.next())
                        assertEquals(3, r.getInt(1))
                        assertEquals(1, r.getInt(2))
                        assertEquals(3.5, r.getDouble(3))
                    }
                }
            }
            c.createStatement().use { it.execute("CREATE SEQUENCE division_operands START 3") }
            val subject = "CAST(nextval('division_operands') AS INTEGER)"
            val sql = "SELECT $subject // $subject,$subject % $subject,$subject / $subject"
            assertEquals(listOf(listOf("0", "5", "0.875")), c.rows(if (generated) SqlFragment(sql, "duckdb").toExecutable("duckdb").sql else sql))
            assertEquals(listOf(listOf("8")), c.rows("SELECT currval('division_operands')"))
        }
    }
    @Test fun executableTypedLoweringUsesExactNativeIntegerDivisionAndDeclinesUnqualifiedSafeOrZeroCases() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val source = "SELECT CAST(9223372036854775807 AS BIGINT) / CAST(1 AS BIGINT)"
            val sql = SqlFragment(source, "postgres").toExecutable("duckdb").sql
            assertTrue("//" in sql)
            assertEquals(listOf(listOf("9223372036854775807")), c.rows(sql))
            val literal = SqlFragment("SELECT 7 / 2", "postgres").toExecutable("duckdb").sql
            assertTrue("//" in literal)
            assertEquals(listOf(listOf("3")), c.rows(literal))
            c.mode(false)
            assertFailsWith<UnsupportedError> { SqlFragment("SELECT CAST(7 AS BIGINT) / CAST(0 AS BIGINT)", "postgres").toExecutable("duckdb") }
            assertFailsWith<UnsupportedError> { SqlFragment("SELECT SAFE_DIVIDE(?,?)", "bigquery").toExecutable("duckdb") }
            assertEquals(listOf(listOf("false")), c.rows("SELECT current_setting('error_on_division_by_zero')"))
        }
    }
}
