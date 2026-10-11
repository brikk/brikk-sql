package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.qualify
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class CorrelatedUnnestTrinoTest {
    private fun execute(container: String, sql: String): List<String> {
        val output = File.createTempFile("correlated-unnest-trino-", ".log",
            File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
        try {
            val process = ProcessBuilder("docker", "exec", container, "trino", "--output-format=TSV_HEADER", "--execute", sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Correlated UNNEST Trino probe timed out")
            }
            val text = output.readText().trimEnd('\n', '\r')
            assertEquals(0, process.exitValue(), "$sql\n$text")
            return text.lines()
        } finally { output.delete() }
    }

    @Test
    fun liveTrinoCorrelatedArraysKeepNearestBindingsAndNativeRows() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        val prefix = "WITH t AS (SELECT ARRAY[CAST(ROW('a',2.0E0) AS ROW(email VARCHAR,magnitude DOUBLE))] AS items) "
        for (source in listOf(
            prefix + "SELECT (SELECT MAX(e.email) FROM UNNEST(items) AS e(email,magnitude)) AS email, " +
                "(SELECT MAX(e.magnitude) FROM UNNEST(items) AS e(email,magnitude)) AS magnitude FROM t",
            prefix + "SELECT (SELECT (SELECT MAX(e.email) FROM UNNEST(items) AS e(email)) " +
                "FROM (SELECT ARRAY[CAST(7 AS BIGINT)] AS items) AS t) AS email FROM t",
            "WITH t AS (SELECT CAST(ARRAY[] AS ARRAY(BIGINT)) AS items) " +
                "SELECT (SELECT MAX(e.item) FROM UNNEST(items) AS e(item)) AS value FROM t",
            "WITH t AS (SELECT CAST(NULL AS ARRAY(BIGINT)) AS items) " +
                "SELECT (SELECT MAX(e.item) FROM UNNEST(items) AS e(item)) AS value FROM t",
        )) {
            val expected = execute(container!!, source)
            for (policy in listOf(true, false, null)) {
                val tree = qualify(Dialects.TRINO.parseOne(source), dialect = Dialects.TRINO, inferSchema = policy)
                annotateTypes(tree, dialect = Dialects.TRINO)
                val sql = tree.sql("trino")
                assertEquals(expected, execute(container, sql), sql)
                if ("CAST(7 AS BIGINT)" in source) {
                    assertEquals(listOf("BIGINT"), tree.selects.map { it.type?.sql("trino") })
                }
            }
        }
    }
}
