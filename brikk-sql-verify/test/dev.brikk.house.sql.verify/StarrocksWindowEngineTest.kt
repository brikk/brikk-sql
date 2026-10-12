package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.TimeUnit
import java.sql.DriverManager
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Opt-in result proof against the pinned live engine, not the advisory MySQL parser. */
class StarrocksWindowEngineTest {
    private fun query(container: String, sql: String): List<String> {
        val temp = java.io.File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val output = java.io.File.createTempFile("starrocks-catchup-", ".log", temp)
        try {
            val process = ProcessBuilder("docker", "exec", container, "mysql", "-uroot",
                "-h127.0.0.1", "-P9030", "-N", "-B", "-e", sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("StarRocks window probe timed out")
            }
            val text = output.readText()
            assertEquals(0, process.exitValue(), "$sql\n$text")
            return text.trim().lines()
        } finally {
            output.delete()
        }
    }

    @Test
    fun actualStarrocksWindowRowsProveIgnoreAndRespectNullSemantics() {
        val container = System.getenv("BRIKK_STARROCKS_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_STARROCKS_TEST_CONTAINER is not configured")
        val name = container!!
        assertTrue(query(name, "SELECT current_version()").single().startsWith("4.1.4-"))
        val source = """
            WITH t AS (
                SELECT 1 AS id, 10 AS x UNION ALL SELECT 2, CAST(NULL AS INT)
                UNION ALL SELECT 3, 30 UNION ALL SELECT 4, CAST(NULL AS INT)
            )
            SELECT id, x,
                LAG(x IGNORE NULLS, 1, -1) OVER (ORDER BY id) AS previous_value,
                LEAD(x IGNORE NULLS, 1, -1) OVER (ORDER BY id) AS next_value,
                FIRST_VALUE(x IGNORE NULLS) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS first_value,
                LAST_VALUE(x IGNORE NULLS) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS last_value,
                LAST_VALUE(x RESPECT NULLS) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS respecting_value
            FROM t ORDER BY id
        """.trimIndent()
        val output = SqlFragment(source, "starrocks").transpileTo("starrocks")
        assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
        assertEquals(listOf(
            "1\t10\t-1\t30\t10\t30\tNULL",
            "2\tNULL\t10\t30\t10\t30\tNULL",
            "3\t30\t10\t-1\t10\t30\tNULL",
            "4\tNULL\t30\t-1\t10\t30\tNULL",
        ), query(name, output.sql), output.sql)
    }

    @Test
    fun actualStarrocksTimeSliceRowsMatchSourceAwareDuckdbIncludingNonDefaultOrigins() {
        val container = System.getenv("BRIKK_STARROCKS_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_STARROCKS_TEST_CONTAINER is not configured")
        val name = container!!
        assertTrue(query(name, "SELECT current_version()").single().startsWith("4.1.4-"))
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                val cases = listOf(
                    Triple("'2019-07-10 02:03:04'", "5 MINUTE", "FLOOR"),
                    Triple("'2019-07-10 02:03:04'", "5 MINUTE", "CEIL"),
                    Triple("'2019-07-10 00:00:00'", "1 DAY", "CEIL"),
                    Triple("'2019-07-10 02:03:04'", "3 DAY", "FLOOR"),
                    Triple("'2019-07-10 02:03:04'", "4 YEAR", "FLOOR"),
                    Triple("'2019-07-10 02:03:04'", "3 MONTH", "FLOOR"),
                    Triple("'2019-07-10 02:03:04'", "7 HOUR", "FLOOR"),
                    Triple("'2017-01-01 00:00:00'", "4 YEAR", "CEIL"),
                    Triple("NULL", "3 DAY", "FLOOR"),
                )
                for ((input, period, boundary) in cases) {
                    val source = "SELECT TIME_SLICE(CAST($input AS DATETIME), INTERVAL $period, $boundary)"
                    val fragment = SqlFragment(source, "starrocks")
                    val native = fragment.transpileTo("starrocks")
                    assertTrue(native.unsupportedMessages.isEmpty(), native.toString())
                    val expected = query(name, native.sql).single()
                    val translated = fragment.transpileTo("duckdb")
                    assertTrue(translated.unsupportedMessages.isEmpty(), translated.toString())
                    assertIs<VerifyResult.Accepted>(SqlVerifiers.forEngine("duckdb")!!.verify(translated.sql), translated.sql)
                    statement.executeQuery(translated.sql).use { result ->
                        assertTrue(result.next())
                        val actual = result.getTimestamp(1)?.toLocalDateTime()
                            ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: "NULL"
                        assertEquals(expected, actual, "$source\n${translated.sql}")
                    }
                }
            }
        }
    }
}
