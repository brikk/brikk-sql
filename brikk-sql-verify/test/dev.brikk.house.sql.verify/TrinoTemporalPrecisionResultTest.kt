package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class TrinoTemporalPrecisionResultTest {
    private fun execute(container: String, sql: String): String {
        val output = File.createTempFile("temporal-trino-", ".log", File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
        try {
            val process = ProcessBuilder("docker", "exec", container, "trino", "--output-format=TSV_HEADER", "--execute", sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Temporal precision probe timed out")
            }
            val text = output.readText().trim()
            assertEquals(0, process.exitValue(), "$sql\n$text")
            return text
        } finally { output.delete() }
    }

    @Test
    fun liveTrinoLiteralValuesTypesAndDateArithmeticRetainTheirPrecision() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        val fields = mutableListOf<String>()
        for ((kind, value) in listOf("TIMESTAMP" to "2020-01-01 00:00:00", "TIME" to "12:00:00")) {
            for (zone in listOf("", " +02:00")) for (precision in listOf(0, 1, 2, 3, 4, 6, 9, 12)) {
                val fraction = if (precision == 0) "" else "." + "123456789012".take(precision)
                val literal = "$kind '$value$fraction$zone'"
                fields += "TYPEOF($literal) AS t${fields.size}"
                fields += "CAST($literal AS VARCHAR) AS v${fields.size}"
            }
        }
        fields += "DATE_ADD('MILLISECOND', 1, TIMESTAMP '2020-01-01 00:00:00') AS zero_precision_add"
        fields += "CAST(NULL AS TIMESTAMP(9)) AS null_timestamp"
        fields += "CAST(NULL AS TIME(6) WITH TIME ZONE) AS null_time"
        val source = "SELECT " + fields.joinToString(", ")
        val output = Dialects.TRINO.parseOne(source).sql("trino")
        assertEquals(execute(container!!, source), execute(container, output), output)
    }
}
