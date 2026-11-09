package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.security.MessageDigest
import kotlin.test.*

/** Five independently executed SG29-011 sub-rounds, private embedded PostgreSQL. */
class PostgresMappingResultTest {
    private companion object {
        val pg by lazy { EmbeddedPostgres.builder().start().also { p -> Runtime.getRuntime().addShutdownHook(Thread { p.close() }) } }
    }
    private fun observe(sql: String): Pair<String, List<String?>> = pg.postgresDatabase.connection.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.metaData.getColumnTypeName(1) to buildList { while (r.next()) add(r.getString(1)) } } }
    }
    @Test fun namedAndMixedMakeIntervalValuesRetainNativeIntervals() {
        for (sql in listOf("SELECT MAKE_INTERVAL(days => 3)", "SELECT MAKE_INTERVAL(1, hours => 2, mins => 30)",
            "SELECT MAKE_INTERVAL(secs => 7.5, mins => 6, hours => 5, DAYS => 4, weeks => 3, months => 2, years => 1)",
            "SELECT MAKE_INTERVAL(days => NULL)", "SELECT MAKE_INTERVAL(days => -3)", "SELECT MAKE_INTERVAL()")) {
            assertEquals(observe(sql), observe(SqlFragment(sql, "postgres").toExecutable("postgres").sql), sql)
        }
    }
    @Test fun datediffBoundaryPreviewsHaveMeasuredForwardAndReverseValues() {
        for ((unit, start, end) in listOf(
            Triple("hour", "2024-01-01 00:59:59", "2024-01-01 01:00:00"),
            Triple("day", "2024-01-01 23:59:59", "2024-01-02 00:00:00"),
            Triple("second", "2024-01-01 00:00:00.999999", "2024-01-01 00:00:01"),
            Triple("month", "2024-01-31 23:59:59", "2024-02-01 00:00:00"),
            Triple("quarter", "2024-03-31 23:59:59", "2024-04-01 00:00:00"),
            Triple("year", "2023-12-31 23:59:59", "2024-01-01 00:00:00"),
        )) for ((a, b, expected) in listOf(Triple(start, end, "1"), Triple(end, start, "-1"))) {
            val sql = "SELECT DATE_DIFF('$unit', TIMESTAMP '$a', TIMESTAMP '$b')"
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("postgres") }
            assertEquals((if (unit == "day") "int4" else "int8") to listOf(expected), observe(transpile(sql, "duckdb", "postgres")), sql)
        }
    }
    @Test fun isoWeekYearFormattingHandlesNewYearBoundaries() {
        for ((date, expected) in listOf("2019-12-30" to "2020-01", "2021-01-01" to "2020-53", "2021-01-04" to "2021-01")) {
            val sql = "SELECT STRFTIME(DATE '$date', '%G-%V')"
            assertEquals("text" to listOf(expected), observe(transpile(sql, "duckdb", "postgres")))
            assertEquals("text" to listOf(expected), observe(SqlFragment("SELECT TO_CHAR(DATE '$date', 'iyyy-iw')", "postgres").toExecutable("postgres").sql))
        }
    }
    @Test fun nativeBinaryShaAndGeneratedHexHaveActualByteaAndTextTypes() {
        for (size in listOf(256, 384, 512)) {
            val expected = MessageDigest.getInstance("SHA-$size").digest("abc".toByteArray())
            val genericHex = SHA2(args("this" to Literal.string("abc"), "length" to Literal.number(size.toString())))
            val hexValue = expected.joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("text" to listOf(hexValue), observe("SELECT " + genericHex.sql("postgres")))
            val sql = SqlFragment("SELECT SHA$size('abc')", "postgres").toExecutable("postgres").sql
            pg.postgresDatabase.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r ->
                assertEquals("bytea", r.metaData.getColumnTypeName(1)); assertTrue(r.next()); assertContentEquals(expected, r.getBytes(1))
            } } }
        }
        val hex = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("text" to listOf(hex), observe(transpile("SELECT SHA256('abc')", "duckdb", "postgres")))
        assertEquals("text" to listOf(null), observe("SELECT ENCODE(SHA256(NULL::BYTEA),'hex')"))
    }
    @Test fun regexCaseControlsMatchNativeBooleanAndNullValues() {
        for ((flag, expected) in listOf("i" to "t", "c" to "f", "ci" to "t", "ic" to "f")) {
            val tree = RegexpLike(args("this" to Literal.string("ABC"), "expression" to Literal.string("abc"), "flag" to Literal.string(flag)))
            assertEquals("bool" to listOf(expected), observe("SELECT " + tree.sql("postgres")))
        }
        assertEquals("bool" to listOf(null), observe("SELECT NULL::TEXT ~* 'abc'"))
    }
}
