package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class ClickhouseCalendarResultTest {
    private fun native(python: String, sql: String): String {
        val output = File.createTempFile("calendar-chdb-", ".log", File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
        try {
            val process = ProcessBuilder(python, "-c", "import chdb,sys; print(chdb.query(sys.argv[1], 'CSV'), end='')", sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Calendar chDB probe timed out")
            }
            val result = output.readText().trim()
            assertEquals(0, process.exitValue(), "$sql\n$result")
            return result
        } finally { output.delete() }
    }

    @Test
    fun nativeClickhouseMatchesDuckdbAtIsoWeekYearLeapAndNullBoundaries() {
        val python = System.getenv("BRIKK_CHDB_TEST_PYTHON")
        assumeTrue(!python.isNullOrBlank(), "BRIKK_CHDB_TEST_PYTHON is not configured")
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for (value in listOf("2015-12-31", "2016-01-01", "2016-01-03", "2016-01-04", "2020-02-29", "2020-12-31", "2021-01-01", "2021-01-04", null)) {
                val date = if (value == null) "CAST(NULL AS Nullable(Date))" else "toDate('$value')"
                val source = "SELECT toDayOfWeek($date), toISOWeek($date), toISOYear($date), toDayOfMonth($date), toDayOfYear($date)"
                val tree = Dialects.CLICKHOUSE.parseOne(source)
                val output = tree.sql("clickhouse")
                val actual = native(python!!, output)
                assertEquals(native(python, source), actual, output)
                // Day/month source calls remain native anonymous nodes; their
                // canonical counterparts have already-equivalent generation.
                val duckDate = if (value == null) "NULL::DATE" else "DATE '$value'"
                val duck = "SELECT ISODOW($duckDate), WEEKOFYEAR($duckDate), EXTRACT(ISOYEAR FROM $duckDate), DAYOFMONTH($duckDate), DAYOFYEAR($duckDate)"
                connection.createStatement().use { statement -> statement.executeQuery(duck).use { row ->
                    row.next()
                    val expected = (1..5).joinToString(",") { row.getString(it) ?: "\\N" }
                    assertEquals(expected, actual, source)
                } }
            }
        }
        for (source in listOf("SELECT toDayOfWeek(toDate('2021-01-03'),3)",
            "SELECT toISOWeek(toDateTime('2021-01-03 23:00:00','UTC'),'Europe/Berlin')",
            "SELECT toISOYear(toDateTime('2021-01-03 23:00:00','UTC'),'Europe/Berlin')")) {
            assertEquals(native(python!!, source), native(python, Dialects.CLICKHOUSE.parseOne(source).sql("clickhouse")))
        }
    }
}
