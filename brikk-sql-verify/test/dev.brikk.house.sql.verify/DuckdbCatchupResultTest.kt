package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.ast.TimestampFromParts
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuckdbCatchupResultTest {
    @Test
    fun timestampPartsPreserveAbsoluteInstantAndMicrosecondsAcrossSessionZones() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                val timestamp = TimestampFromParts(args(
                    "year" to Literal.number(2019), "month" to Literal.number(7), "day" to Literal.number(10),
                    "hour" to Literal.number(2), "min" to Literal.number(3), "sec" to Literal.number(4),
                    "nano" to Literal.number(123456000), "zone" to Literal.string("America/Los_Angeles"),
                ))
                for (zone in listOf("UTC", "America/New_York")) {
                    statement.execute("SET TimeZone = '$zone'")
                    val expression = timestamp.sql("duckdb")
                    val query = "SELECT epoch_us($expression), epoch_us(TIMESTAMPTZ '2019-07-10 09:03:04.123456+00'), typeof($expression)"
                    assertTrue(SqlVerifiers.forEngine("duckdb")!!.verify(query).accepted, query)
                    statement.executeQuery(query).use { result ->
                        assertTrue(result.next())
                        assertEquals(result.getLong(2), result.getLong(1), zone)
                        assertEquals("TIMESTAMP WITH TIME ZONE", result.getString(3))
                    }
                }
                timestamp.set("nano", Null())
                statement.executeQuery("SELECT ${timestamp.sql("duckdb")}").use { result ->
                    assertTrue(result.next())
                    assertEquals(null, result.getObject(1))
                }
            }
        }
    }

    @Test
    fun localTimezonePrecisionCastsReachNativeDuckdbWithoutUnsupportedModifiers() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (precision in listOf(0, 3, 6, 9)) {
                    val type = DataType(args("this" to DType.TIMESTAMPLTZ,
                        "expressions" to listOf(Literal.number(precision))))
                    val query = "SELECT CAST(NULL AS ${type.sql("duckdb")})"
                    assertTrue(SqlVerifiers.forEngine("duckdb")!!.verify(query).accepted, query)
                    statement.executeQuery(query).use { result ->
                        assertTrue(result.next())
                        assertEquals(null, result.getObject(1))
                    }
                }
            }
        }
    }

    @Test
    fun sampleBeforeOrderingAndLimitsExecutesTheCorrectPagination() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t AS SELECT * FROM (VALUES (3), (1), (2)) AS data(id)")
                for (source in listOf(
                    "SELECT * FROM t USING SAMPLE SYSTEM (100 PERCENT) ORDER BY id LIMIT 2 OFFSET 1",
                    "FROM t |> TABLESAMPLE SYSTEM (100 PERCENT) |> ORDER BY id |> LIMIT 2 OFFSET 1",
                )) {
                    val output = SqlFragment(source, "duckdb").toExecutable("duckdb", trackSourceMap = false).sql
                    assertTrue(SqlVerifiers.forEngine("duckdb")!!.verify(output).accepted, output)
                    statement.executeQuery(output).use { result ->
                        assertTrue(result.next())
                        assertEquals(2, result.getInt("id"))
                        assertTrue(result.next())
                        assertEquals(3, result.getInt("id"))
                        assertFalse(result.next())
                    }
                }
            }
        }
    }
}
