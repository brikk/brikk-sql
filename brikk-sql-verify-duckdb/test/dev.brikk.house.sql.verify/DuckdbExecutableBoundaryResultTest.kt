package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

internal class DuckdbExecutableBoundaryResultTest {
    private fun connection() = DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
        setProperty("enable_external_access", "false")
        setProperty("autoload_known_extensions", "false")
        setProperty("autoinstall_known_extensions", "false")
    })
    private fun Connection.value(sql: String) = createStatement().use { s ->
        s.executeQuery(sql).use { r ->
            assertTrue(r.next())
            r.getString(1)
        }
    }
    @Test fun fractionalIntervalRefusalPreventsMeasuredNativeValueLoss() {
        connection().use { c ->
            val sql = "SELECT epoch(INTERVAL '1.5 days')"
            val historical = Dialects.DUCKDB.parseOne(sql).sql("duckdb")
            assertEquals("129600.0", c.value(sql))
            assertEquals("86400.0", c.value(historical), historical)
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
            assertEquals("172800.0", c.value(SqlFragment("SELECT epoch(INTERVAL '2 days')", "duckdb").toExecutable("duckdb").sql))
        }
    }
    @Test fun multiPathRefusalDoesNotTurnANativeBindingErrorIntoAnAcceptedSinglePathQuery() {
        connection().use { c ->
            val sql = "SELECT json_extract('{}','$.x','$.y')"
            assertIs<SerializedSql.Parsed>(c.serializeSql(sql))
            assertTrue(assertFailsWith<SQLException> { c.value(sql) }.message.orEmpty().contains("Binder Error"))
            val historical = Dialects.DUCKDB.parseOne(sql).sql("duckdb")
            assertNull(c.value(historical), historical)
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
        }
    }
    @Test fun droppedRegexFlagHasAnActualCounterexampleAndUnchangedNativeControlsStillExecute() {
        connection().use { c ->
            assertEquals("true", c.value("SELECT regexp_matches('Ab','a','i')"))
            assertEquals("false", c.value("SELECT 'Ab' ~ 'a'"))
            assertEquals("1", c.value("SELECT date_diff('month',DATE '2026-01-31',DATE '2026-02-01')"))
            assertEquals("0", c.value("SELECT date_sub('month',DATE '2026-01-31',DATE '2026-02-01')"))
            assertFailsWith<UnsupportedError> { SqlFragment("SELECT regexp_matches('Ab','a','i')", "duckdb").toExecutable("postgres") }
            val sql = "SELECT CAST(? AS INTEGER)+1 AS x"
            c.prepareStatement(SqlFragment(sql, "duckdb").toExecutable("duckdb").sql).use { p ->
                p.setInt(1, 2)
                p.executeQuery().use { r ->
                    assertTrue(r.next())
                    assertEquals(3, r.getInt(1))
                    assertEquals("INTEGER", r.metaData.getColumnTypeName(1))
                }
            }
        }
    }
}
