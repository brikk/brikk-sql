package dev.brikk.house.sql.verify

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetTypingResultTest {
    @Test
    fun nativeDuckdbConfirmsTargetNumericZoneAndTruncationKinds() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for ((expression, expected) in listOf("FLOOR(1::BIGINT)" to "DOUBLE",
                "CEIL(1::INTEGER)" to "DOUBLE", "DATE '2024-01-01' AT TIME ZONE 'UTC'" to "TIMESTAMPTZ_NS",
                "TIMESTAMPTZ '2024-01-01 00:00:00+00' AT TIME ZONE 'UTC'" to "TIMESTAMP",
                "DATE_TRUNC('day',DATE '2024-01-01')" to "TIMESTAMP")) {
                connection.createStatement().use { statement -> statement.executeQuery("SELECT $expression AS value").use { rows ->
                    assertEquals(expected, rows.metaData.getColumnTypeName(1), expression)
                } }
            }
        }
    }
}
