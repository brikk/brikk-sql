package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

/** Pinned 2.0.0 d54cbbe3df binder/test_null_type_propagation and legacy_null_type tests. */
internal class DuckdbNullTypeResultTest {
    private fun Connection.rows(sql: String) = createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun rendered(sql: String, generated: Boolean) = if (generated) SqlFragment(sql, "duckdb").toExecutable("duckdb").sql else sql
    private fun Connection.run(sql: String, generated: Boolean) = createStatement().use { it.execute(rendered(sql, generated)) }
    @Test fun nativeCtasRetainsNullTypeAcceptsNullAndRejectsNonNullWithoutChangingTheTable() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            assertEquals(listOf(listOf("false")), c.rows("SELECT current_setting('legacy_disable_null_type')"))
            c.run("CREATE TABLE nulls AS SELECT NULL AS v", generated)
            assertEquals(listOf(listOf("\"NULL\"", "true")), c.rows("SELECT data_type,is_nullable FROM duckdb_columns() WHERE table_name='nulls'"))
            for (value in listOf("1", "'x'", "TRUE")) {
                val error = assertFailsWith<SQLException> { c.run("INSERT INTO nulls VALUES($value)", generated) }
                assertTrue(error.message!!.contains("NULL"))
                assertEquals(listOf(listOf("1")), c.rows("SELECT count(*) FROM nulls"))
            }
            c.run("INSERT INTO nulls VALUES(NULL)", generated)
            assertEquals(listOf(listOf("\"NULL\"", null), listOf("\"NULL\"", null)), c.rows("SELECT typeof(v),v FROM nulls"))
            c.createStatement().use { s -> s.executeQuery("SELECT v FROM nulls").use { r ->
                assertEquals("\"NULL\"", r.metaData.getColumnTypeName(1))
                    assertTrue(r.next())
                    assertNull(r.getObject(1))
            } }
        }
    }
    @Test fun deprecatedLegacyModeOnlyAffectsNativeInferenceAndExplicitIntegerCastsWorkInBothModes() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.run("CREATE TABLE before_legacy AS SELECT NULL AS v", generated)
            c.run("SET legacy_disable_null_type=true", generated)
            c.run("CREATE TABLE legacy_nulls AS SELECT NULL AS v", generated)
            c.run("INSERT INTO legacy_nulls VALUES(7)", generated)
            assertEquals(listOf(listOf("INTEGER", null), listOf("INTEGER", "7")), c.rows("SELECT typeof(v),v FROM legacy_nulls"))
            assertFailsWith<SQLException> { c.run("INSERT INTO before_legacy VALUES(7)", generated) }
            for (mode in listOf(false, true)) {
                c.run("SET legacy_disable_null_type=$mode", generated)
                c.run("CREATE OR REPLACE TABLE typed_nulls AS SELECT CAST(NULL AS INTEGER) AS v", generated)
                c.run("INSERT INTO typed_nulls VALUES(9)", generated)
                assertEquals(listOf(listOf("INTEGER", null), listOf("INTEGER", "9")), c.rows("SELECT typeof(v),v FROM typed_nulls"))
            }
            c.run("RESET legacy_disable_null_type", generated)
            c.run("CREATE TABLE after_reset AS SELECT NULL AS v", generated)
            assertEquals(listOf(listOf("\"NULL\"")), c.rows("SELECT typeof(v) FROM after_reset"))
        }
    }
    @Test fun nullOnlyViewsUnionsAndTypedBooleanUnionsDoNotAcquireAnImplicitIntegerCast() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.run("CREATE VIEW null_view AS SELECT NULL AS v UNION ALL SELECT NULL", generated)
            assertEquals(listOf(listOf("\"NULL\"", null), listOf("\"NULL\"", null)), c.rows("SELECT typeof(v),v FROM null_view"))
            val sql = "SELECT NULL AS v UNION ALL SELECT TRUE"
            c.createStatement().use { s -> s.executeQuery(rendered(sql, generated)).use { r ->
                assertEquals("BOOLEAN", r.metaData.getColumnTypeName(1))
                    assertTrue(r.next())
                    assertNull(r.getObject(1))
                    assertTrue(r.next())
                    assertTrue(r.getBoolean(1))
            } }
        }
    }
}
