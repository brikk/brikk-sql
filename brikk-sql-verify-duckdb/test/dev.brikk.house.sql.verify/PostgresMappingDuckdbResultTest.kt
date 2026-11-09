package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.transpile
import java.sql.DriverManager
import kotlin.test.*

internal class PostgresMappingDuckdbResultTest {
    @Test fun nativeBoundaryCountsAndBinaryDigestAreIndependentValueControls() {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            for ((unit, start, end) in listOf(
                Triple("hour", "2024-01-01 00:59:59", "2024-01-01 01:00:00"),
                Triple("day", "2024-01-01 23:59:59", "2024-01-02 00:00:00"),
                Triple("month", "2024-01-31 23:59:59", "2024-02-01 00:00:00"),
                Triple("quarter", "2024-03-31 23:59:59", "2024-04-01 00:00:00"),
                Triple("year", "2023-12-31 23:59:59", "2024-01-01 00:00:00"),
            )) for ((a, b, expected) in listOf(Triple(start, end, 1L), Triple(end, start, -1L))) {
                s.executeQuery("SELECT DATE_DIFF('$unit', TIMESTAMP '$a', TIMESTAMP '$b')").use { r ->
                    assertTrue(r.next()); assertEquals(expected, r.getLong(1)); assertEquals("BIGINT", r.metaData.getColumnTypeName(1))
                }
            }
            val sql = transpile("SELECT SHA256('abc')", "postgres", "duckdb")
            s.executeQuery(sql).use { r ->
                assertTrue(r.next()); assertEquals("BLOB", r.metaData.getColumnTypeName(1))
                assertContentEquals(java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()), r.getBytes(1))
            }
        } }
    }
    @Test fun namedIntervalAndIsoFormattingHaveNativeDuckdbValues() {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            for ((source, expected) in listOf("MAKE_INTERVAL(days => 3)" to "3 days",
                "MAKE_INTERVAL(1, hours => 2, mins => 30)" to "1 year 02:30:00")) {
                val sql = transpile("SELECT $source", "postgres", "duckdb")
                s.executeQuery(sql).use { r -> assertTrue(r.next()); assertEquals(expected, r.getString(1), sql); assertEquals("INTERVAL", r.metaData.getColumnTypeName(1)) }
            }
            for ((date, expected) in listOf("2019-12-30" to "2020-01", "2021-01-01" to "2020-53")) {
                s.executeQuery(transpile("SELECT TO_CHAR(DATE '$date', 'IYYY-IW')", "postgres", "duckdb")).use { r ->
                    assertTrue(r.next()); assertEquals(expected, r.getString(1))
                }
            }
        } }
    }
}
