package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.shape.SqlFragment
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in native result checks; these are not substitutes for the always-on grammar gates. */
class IntegerDivisionEngineTest {
    private fun execute(container: String, client: List<String>, sql: String): Pair<Int, String> {
        val temp = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val output = File.createTempFile("integer-division-", ".log", temp)
        try {
            val process = ProcessBuilder(listOf("docker", "exec", container) + client + sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Integer division engine probe timed out")
            }
            return process.exitValue() to output.readText().trimEnd('\n', '\r')
        } finally {
            output.delete()
        }
    }

    private fun query(container: String, client: List<String>, sql: String): String {
        val (status, output) = execute(container, client, sql)
        assertEquals(0, status, "$sql\n$output")
        return output
    }

    @Test
    fun liveTrinoProvesTheOldRoundingCastIsWrongAndNewFallbackTruncatesToBigint() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        val name = container!!
        val client = listOf("trino", "--output-format=TSV", "--execute")
        assertEquals("484", query(name, client, "SELECT version()"))
        assertEquals("4\t-4", query(name, client,
            "SELECT CAST(CAST(7 AS DOUBLE) / 2 AS INTEGER), CAST(CAST(-7 AS DOUBLE) / 2 AS INTEGER)"))
        for ((call, expected) in listOf(
            "7 DIV 2" to "3", "-7 DIV 2" to "-3", "7 DIV -2" to "-3", "-7 DIV -2" to "3",
            "-1 DIV 2" to "0", "6000000001 DIV 2" to "3000000000", "NULL DIV 2" to "",
        )) {
            val output = SqlFragment("SELECT $call AS q", "hive").transpileTo("trino")
            assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
            assertEquals("bigint\t$expected", query(name, client, "SELECT typeof(q), q FROM (${output.sql}) AS t"))
        }
        val zero = SqlFragment("SELECT 7 DIV 0", "hive").transpileTo("trino").sql
        val (status, failure) = execute(name, client, zero)
        // Floating division produces Infinity in Trino; converting that quotient
        // to BIGINT still fails rather than silently returning a NULL or integer.
        assertTrue(status != 0 && "Out of range for bigint: Infinity" in failure, "$zero\n$failure")
        val safe = annotateTypes(Dialects.POSTGRES.parseOne("SELECT 7 / 0 AS q"), dialect = Dialects.POSTGRES)
        safe.findAll<Div>().single().set("safe", true)
        assertEquals("", query(name, client, safe.sql("trino")))
    }

    @Test
    fun liveMysqlProvesDivPrecedenceTruncateScaleNullsAndWideResults() {
        val container = System.getenv("BRIKK_MYSQL_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_MYSQL_TEST_CONTAINER is not configured")
        val name = container!!
        val client = listOf("mysql", "-uroot", "-N", "-B", "-e")
        assertTrue(query(name, client, "SELECT VERSION()").startsWith("8.4."))
        for ((call, expected) in listOf(
            "DIV(7, 2)" to "3", "DIV(-7, 2)" to "-3", "DIV(7, -2)" to "-3", "DIV(-7, -2)" to "3",
            "DIV(10, DIV(4, 2))" to "5", "10 / DIV(4, 2)" to "5.0000",
            "300 * DIV(1000, 300) + 120" to "1020", "DIV(10 + 2, 3 + 1)" to "3",
            "-DIV(7, 2)" to "-3", "DIV(NULL, 2)" to "NULL", "DIV(7, 0)" to "NULL",
            "DIV(9223372036854775807, 1)" to "9223372036854775807",
        )) {
            val output = SqlFragment("SELECT $call", "bigquery").transpileTo("mysql")
            assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
            assertEquals(expected, query(name, client, output.sql), output.sql)
        }
        val typed = annotateTypes(Dialects.POSTGRES.parseOne("SELECT t.x / t.y FROM " +
            "(SELECT CAST(-7 AS BIGINT) AS x, CAST(2 AS BIGINT) AS y) AS t"), dialect = Dialects.POSTGRES)
        val output = typed.sql("mysql")
        assertEquals("-3", query(name, client, output), output)
        assertEquals("3", query(name, client, Dialects.POSTGRES.parseOne("SELECT TRUNC(3.14159)").sql("mysql")))
    }
}
