package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Native source results compared with actual emitted DuckDB rows; no Snowflake dialect is added. */
class EmptyRegexpEngineTest {
    private fun container(variable: String): String {
        val name = System.getenv(variable)
        assumeTrue(!name.isNullOrBlank(), "$variable is not configured")
        return name!!
    }

    private fun execute(container: String, client: List<String>, sql: String): Pair<Int, String> {
        val temp = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val output = File.createTempFile("empty-regexp-", ".log", temp)
        try {
            val process = ProcessBuilder(listOf("docker", "exec", container) + client + sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Empty regexp engine probe timed out")
            }
            return process.exitValue() to output.readText().trimEnd('\n', '\r')
        } finally {
            output.delete()
        }
    }

    private fun query(container: String, client: List<String>, sql: String): String {
        val (status, text) = execute(container, client, sql)
        assertEquals(0, status, "$sql\n$text")
        return text
    }

    private fun compare(container: String, client: List<String>, source: String, dialect: String, nullText: String) {
        val expected = query(container, client, source)
        val output = SqlFragment(source, dialect).transpileTo("duckdb")
        assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(output.sql).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(expected, rows.getObject(1)?.toString() ?: nullText, "$source\n${output.sql}")
                    assertEquals("BIGINT", rows.metaData.getColumnTypeName(1), output.sql)
                }
            }
        }
    }

    @Test
    fun livePostgresMatchesCountsAndWholeMatchPositionsIncludingEmptyFlagsAndNulls() {
        val name = container("BRIKK_POSTGRES_TEST_CONTAINER")
        val client = listOf("psql", "-U", "postgres", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c")
        assertTrue(query(name, client, "SELECT current_setting('server_version')").startsWith("18."))
        for (call in listOf(
            "REGEXP_COUNT('abc', '', 1, 'i')", "REGEXP_COUNT('abc', '', 4, 'i')", "REGEXP_COUNT('abc', '', 5, 'i')",
            "REGEXP_COUNT(NULL, '', 1, 'i')", "REGEXP_COUNT('abc', '', 1, NULL)", "REGEXP_COUNT(NULL, '', 0, 'i')",
            "REGEXP_COUNT('Hello World', 'L', 1, 'i')", "REGEXP_COUNT(E'a\\nb', 'a.b')",
            "REGEXP_COUNT(E'a\\nb', 'a.b', 1, 'm')", "REGEXP_COUNT(E'a\\nb', 'a.b', 1, 's')",
            "REGEXP_INSTR('abc', '', 1, 1, 0, 'i')", "REGEXP_INSTR('abc', '', 1, 4, 1, 'i')",
            "REGEXP_INSTR('abc', '', 2, 3, 0, 'i')", "REGEXP_INSTR('abc', '', 4, 1, 0, 'i')",
            "REGEXP_INSTR('abc', '', 5, 1, 0, 'i')", "REGEXP_INSTR(NULL, '', 1, 1, 0, 'i')",
            "REGEXP_INSTR('abc', '', 1, 1, 0, NULL)", "REGEXP_INSTR('ab12cd345ef', '[0-9]+', 1, 2, 0, 'i')",
            "REGEXP_INSTR('ab12cd345ef', '[0-9]+', 1, 2, 1, 'i')", "REGEXP_INSTR('ab12cd345ef', '[0-9]+', 4, 1, 0, 'i')",
            "REGEXP_INSTR('é😀LéL', 'l', 1, 2, 0, 'i')",
        )) compare(name, client, "SELECT $call", "postgres", "")
        val dynamic = "SELECT REGEXP_INSTR('ab12cd345ef', '[0-9]+', 1, 2, t.opt, 'i') FROM (VALUES (1)) AS t(opt)"
        compare(name, client, dynamic, "postgres", "")
    }

    @Test
    fun liveMysqlEmptyPatternRejectionsAndNonemptyPositionsArePreserved() {
        val name = container("BRIKK_MYSQL_TEST_CONTAINER")
        val client = listOf("mysql", "-uroot", "-N", "-B", "-e")
        assertTrue(query(name, client, "SELECT VERSION()").startsWith("8.4."))
        for (call in listOf(
            "REGEXP_INSTR('Hello World', 'L', 1, 1, 0, 'i')",
            "REGEXP_INSTR('ab12cd345ef', '[0-9]+', 1, 2, 0, 'i')",
            "REGEXP_INSTR('ab12cd345ef', '[0-9]+', 1, 2, 1, 'i')",
            "REGEXP_INSTR('abc', '^b', 2)", "REGEXP_INSTR('abc', 'x')",
        )) compare(name, client, "SELECT $call", "mysql", "NULL")
        for (call in listOf("REGEXP_INSTR('abc', '', 1, 1, 0, 'i')", "REGEXP_INSTR(NULL, '', 1, 1, 0, 'i')",
            "REGEXP_INSTR('abc', 'a', 5)")) {
            val source = "SELECT $call"
            val (status, error) = execute(name, client, source)
            assertTrue(status != 0 && ("Illegal argument" in error || "Index out of bounds" in error), error)
            val output = SqlFragment(source, "mysql").transpileTo("duckdb")
            assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                val failure = kotlin.test.assertFailsWith<java.sql.SQLException> {
                    connection.createStatement().use { it.executeQuery(output.sql).close() }
                }
                assertTrue("MySQL REGEXP_INSTR" in failure.message.orEmpty(), failure.message)
            }
        }
    }

    @Test
    fun liveTrinoCountKeepsNativeEmptyPatternMatches() {
        val name = container("BRIKK_TRINO_TEST_CONTAINER")
        val client = listOf("trino", "--output-format=TSV", "--execute")
        assertEquals("483", query(name, client, "SELECT version()"))
        for (call in listOf("REGEXP_COUNT('abc', '')", "REGEXP_COUNT(NULL, '')", "REGEXP_COUNT('abc', '[a-z]')",
            "REGEXP_COUNT('é😀a', '')")) compare(name, client, "SELECT $call", "trino", "")
    }
}
