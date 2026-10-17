package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringEscapeEngineTest {
    private fun container(): String {
        val name = System.getenv("BRIKK_POSTGRES_TEST_CONTAINER")
        assumeTrue(!name.isNullOrBlank(), "BRIKK_POSTGRES_TEST_CONTAINER is not configured")
        return name!!
    }

    private fun execute(container: String, sql: String): Pair<Int, String> {
        val temp = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val output = File.createTempFile("string-escape-", ".log", temp)
        try {
            val process = ProcessBuilder("docker", "exec", container, "psql", "-U", "postgres",
                "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("String escape engine probe timed out")
            }
            return process.exitValue() to output.readText().trimEnd('\n', '\r')
        } finally {
            output.delete()
        }
    }

    private fun query(container: String, sql: String): String {
        val (status, output) = execute(container, sql)
        assertEquals(0, status, "$sql\n$output")
        return output
    }

    @Test
    fun livePostgresProvesUtf8OctalUnicodeAndLiteralBackslashRoundTrips() {
        val container = container()
        assertTrue(query(container, "SELECT current_setting('server_version')").startsWith("18."))
        for ((literal, hex) in listOf(
            "e'\\a\\v\\x41\\101\\541'" to "610b414161",
            "e'\\xc3\\xa9 \\360\\237\\230\\200'" to "c3a920f09f9880",
            "e'\\uD83D\\uDE00'" to "f09f9880",
            "e'\\U0001f600'" to "f09f9880",
            "e'\\x4Z\\xzz\\z'" to "045a787a7a7a",
            "e'\\\\n\\\\x41'" to "5c6e5c783431",
            "e'\\176'" to "7e",
            "e'\\x44EADBEEF'" to "4445414442454546",
            "\$\$\\n\\x41\$\$" to "5c6e5c783431",
        )) {
            val source = "SELECT $literal AS value"
            val output = Dialects.POSTGRES.parseOne(source).sql("postgres")
            for (sql in listOf(source, output)) {
                assertEquals(hex, query(container, "SELECT encode(convert_to(value, 'UTF8'), 'hex') FROM ($sql) AS q"), sql)
            }
        }
        val cast = Dialects.POSTGRES.parseOne("SELECT CAST(e'\\176' AS BYTEA) AS value").sql("postgres")
        assertEquals("7e", query(container, "SELECT encode(value, 'hex') FROM ($cast) AS q"))
    }

    @Test
    fun nativePostgresRejectsInvalidUtf8AndNulRatherThanProvingOldCorpusText() {
        val container = container()
        for (source in listOf("SELECT e'\\xDEADBEEF'", "SELECT e'a\\x00b'")) {
            val (status, output) = execute(container, source)
            assertTrue(status != 0 && "invalid byte sequence" in output, "$source\n$output")
        }
        // Old dumped ASTs store text, not unprocessed SQL escape syntax.
        val text = dev.brikk.house.sql.ast.ByteString(dev.brikk.house.sql.ast.args("this" to "\\x41", "is_bytes" to false))
        assertEquals("5c783431", query(container, "SELECT encode(convert_to(${text.sql("postgres")}, 'UTF8'), 'hex')"))
    }
}
