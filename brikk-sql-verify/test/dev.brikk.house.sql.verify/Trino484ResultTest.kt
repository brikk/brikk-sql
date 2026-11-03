package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opt-in result gate against the stock 484 engine, not just its ANTLR parser. */
class Trino484ResultTest {
    private fun execute(container: String, sql: String, succeeds: Boolean = true): String {
        val output = File.createTempFile("trino-484-", ".log")
        try {
            val process = ProcessBuilder("docker", "exec", container, "trino", "--output-format=TSV", "--execute", sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Trino 484 result probe timed out")
            }
            val result = output.readText().trim()
            assertEquals(succeeds, process.exitValue() == 0, "$sql\n$result")
            return result
        } finally {
            output.delete()
        }
    }

    @Test
    fun jsonAndIntervalValuesMatch484AfterRegeneration() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        assertEquals("484", execute(container!!, "SELECT version()"))
        val cases = listOf(
            "JSON_FORMAT(JSON_ARRAY(1, DECIMAL '1.20' RETURNING JSON))" to "[1,1.20]",
            "JSON_FORMAT(JSON_OBJECT('a' VALUE JSON '[1,2]' RETURNING JSON))" to "{\"a\":[1,2]}",
            "JSON_FORMAT(JSON_PARSE('{\"z\":1,\"a\":2,\"a\":3}'))" to "{\"z\":1,\"a\":2,\"a\":3}",
            "JSON_FORMAT(JSON_ARRAY_GET('[\"abc\"]', 0))" to "\"abc\"",
            "CAST(JSON '1.230' AS VARCHAR)" to "1.230",
            "JSON_FORMAT(CAST(ARRAY[DECIMAL '1.20'] AS JSON))" to "[1.20]",
            "JSON_FORMAT(JSON_SCALAR(CAST('a' AS CHAR(3))))" to "\"a  \"",
            "CAST(JSON_SCALAR(DATE '2024-01-02') AS DATE)" to "2024-01-02",
            "CAST(JSON_SCALAR(TIME '12:34:56') AS TIME)" to "12:34:56.000",
            "JSON_SERIALIZE(JSON('[1, 2]'))" to "[1,2]",
            "JSON_SERIALIZE('not json' NULL ON ERROR) IS NULL" to "true",
            "JSON_VALUE(JSON '{\"n\":1.20}', 'lax $.n' RETURNING DECIMAL(10, 2))" to "1.20",
            "JSON_EXISTS(JSON '{\"n\":1}', 'lax $.n')" to "true",
            "JSON_QUERY(JSON '{\"n\":1}', 'lax $.n')" to "1",
            "JSON_VALUE(JSON '[1,2,3]', 'lax ${'$'}[1 + 1]' RETURNING INTEGER)" to "3",
            "JSON_VALUE(JSON '{}', 'lax ${'$'}n' PASSING 1.20 AS \"n\" RETURNING DECIMAL(10, 2))" to "1.20",
            "JSON_VALUE(JSON '{}', 'lax ${'$'}N' PASSING 1.20 AS n RETURNING DECIMAL(10, 2))" to "1.20",
            "CAST(INTERVAL '1' DAY / 2 AS VARCHAR)" to "0",
            "HOUR(INTERVAL '36' HOUR)" to "36",
            "TYPEOF(INTERVAL '1.123456789012' SECOND(13, 12))" to "interval second(13, 12)",
            "CAST(INTERVAL '1.123456789012' SECOND(13, 12) AS VARCHAR)" to "1.123456789012",
            "CAST(INTERVAL '1.2345' SECOND(13, 2) AS VARCHAR)" to "1.23",
            "CAST((TIMESTAMP '2026-01-02 01:02:03.123456' - TIMESTAMP '2026-01-01 00:00:00') DAY TO SECOND(6) AS VARCHAR)" to "1 01:02:03.123456",
            "TRY(JSON_OBJECT('a' VALUE '{\"b\":1,\"b\":2}' FORMAT JSON WITH UNIQUE KEYS)) IS NULL" to "true",
            "TRY(CAST('340' AS INTERVAL DAY(2))) IS NULL" to "true",
            "TRY(JSON_PARSE('1e9999')) IS NULL" to "true",
            "TRY(JSON_PARSE('\"\\uD800\"')) IS NULL" to "true",
            "TRY(JSON_PARSE(LPAD('0', 1026, '[') || RPAD('', 1025, ']'))) IS NULL" to "true",
        )
        val source = "SELECT " + cases.mapIndexed { i, (sql, _) -> "$sql AS c$i" }.joinToString(", ")
        val expected = cases.joinToString("\t") { it.second }
        assertEquals(expected, execute(container, source))
        val generated = parseOne(source, "trino").sql("trino")
        assertEquals(expected, execute(container, generated), generated)
        // Absent qualifier fields fail during analysis, before TRY can intercept them.
        val invalidExtract = "SELECT EXTRACT(DAY FROM INTERVAL '1' HOUR)"
        for (sql in listOf(invalidExtract, parseOne(invalidExtract, "trino").sql("trino"))) {
            kotlin.test.assertTrue(execute(container, sql, succeeds = false).contains("Cannot extract DAY"))
        }
    }

    @Test
    fun windowExclusionsMatch484AfterRegeneration() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        for (exclusion in listOf("CURRENT ROW", "GROUP", "TIES", "NO OTHERS")) {
            val source = "SELECT k, SUM(v) OVER (ORDER BY k ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE $exclusion) " +
                "FROM (VALUES (1, 10), (1, 20), (2, 30)) AS t(k, v) ORDER BY k, v"
            assertEquals(execute(container!!, source), execute(container, parseOne(source, "trino").sql("trino")), exclusion)
        }
    }

    @Test
    fun nearestRetainsCorrelationAndInputQualifiers() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        val source = "SELECT trades.id, quotes.price FROM (VALUES (1, 10), (2, 20)) AS trades(id, ts) " +
            "CROSS JOIN NEAREST (FROM (VALUES (5, 100), (15, 200)) AS quotes(ts, price) " +
            "MATCH quotes.ts <= trades.ts) ORDER BY trades.id"
        assertEquals("1\t100\n2\t200", execute(container!!, source))
        assertEquals("1\t100\n2\t200", execute(container, parseOne(source, "trino").sql("trino")))
    }

    @Test
    fun jsonTableAcceptsJsonTypedInput() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        val source = "SELECT n FROM JSON_TABLE(JSON '[{\"n\":1},{\"n\":2}]', 'lax ${'$'}[*]' " +
            "COLUMNS (n INTEGER PATH 'lax $.n')) AS jt ORDER BY n"
        assertEquals("1\n2", execute(container!!, source))
        assertEquals("1\n2", execute(container, parseOne(source, "trino").sql("trino")))
    }
}
