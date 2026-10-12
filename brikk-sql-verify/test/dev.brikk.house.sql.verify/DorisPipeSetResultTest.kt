package dev.brikk.house.sql.verify

import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DorisPipeSetResultTest {
    private val client = listOf("mysql", "--default-character-set=utf8mb4", "-uroot", "-h127.0.0.1", "-P9030", "-B", "-e")

    private fun container(): String {
        val value = System.getenv("BRIKK_DORIS_TEST_CONTAINER")
        assumeTrue(!value.isNullOrBlank(), "BRIKK_DORIS_TEST_CONTAINER is not configured")
        return value!!
    }

    private fun fixture(container: String): String {
        val database = "doris_set_${java.lang.Long.toUnsignedString(System.nanoTime(), 36)}"
        runCatchupEngineSql(container, client, "CREATE DATABASE $database; USE $database; " +
            "CREATE TABLE t(label VARCHAR(10), id INT, payload BIGINT) DUPLICATE KEY(label) " +
            "DISTRIBUTED BY HASH(label) BUCKETS 1 PROPERTIES('replication_num'='1'); " +
            "INSERT INTO t VALUES ('a',1,100000000000),('a',1,100000000000),('b',NULL,NULL),('c',2,200000000000)")
        return database
    }

    @Test
    fun liveDorisSchemaLessSetAndDropPreservePositionsValuesTypesNullsAndDuplicateRows() {
        val container = container()
        val database = fixture(container)
        val cases = listOf(
            "SET id = 7" to "SELECT label,7 AS id,payload FROM t",
            "SET id = id + 1" to "SELECT label,id+1 AS id,payload FROM t",
            "SET id = payload, payload = id" to "SELECT label,payload AS id,id AS payload FROM t",
            "SET payload = id, id = payload" to "SELECT label,payload AS id,id AS payload FROM t",
            "SET id = id + 1, payload = id" to "SELECT label,id+1 AS id,id AS payload FROM t",
            "SET id = id + 1 |> SET payload = id" to "SELECT label,id+1 AS id,id+1 AS payload FROM t",
            "SET id = CAST(NULL AS BIGINT), payload = COALESCE(payload,CAST(5 AS BIGINT))" to
                "SELECT label,CAST(NULL AS BIGINT) AS id,COALESCE(payload,CAST(5 AS BIGINT)) AS payload FROM t",
            "DROP id" to "SELECT label,payload FROM t",
            "SET id = id + 1 |> DROP payload" to "SELECT label,id+1 AS id FROM t",
        )
        for ((stage, reference) in cases) {
            val fragment = SqlFragment("FROM t |> $stage", "doris")
            val original = fragment.ast.copy()
            // No ShapeCatalog or full field list is passed to this executable renderer.
            val generated = fragment.toExecutable("doris")
            assertEquals(emptyList(), generated.unsupportedMessages)
            assertSame(generated.sql, generated.sourceMap!!.output)
            val order = if ("DROP" in stage) "ORDER BY 1,2" else "ORDER BY 1,2,3"
            fun rows(sql: String) = runCatchupEngineSql(container, client,
                "USE $database; SELECT * FROM ($sql) AS rendered $order")
            assertEquals(rows(reference), rows(generated.sql), stage)
            // Inspect the query's wire metadata directly, without creating a view.
            assertEquals(columns(container, "USE $database; $reference"),
                columns(container, "USE $database; ${generated.sql}"), stage)
            assertEquals(original, fragment.ast)
        }
    }

    private fun columns(container: String, sql: String): List<List<String>> {
        val metadataClient = client.dropLast(1).filterNot { it == "-B" } + listOf("--column-type-info", "-vvv", "-e")
        val text = runCatchupEngineSql(container, metadataClient, sql)
        val fields = Regex("(?m)^Field\\s+\\d+:\\s+`([^`]*)`").findAll(text).toList()
        assertTrue(fields.isNotEmpty(), text)
        return fields.mapIndexed { index, field ->
            val block = text.substring(field.range.last + 1, fields.getOrNull(index + 1)?.range?.first ?: text.length)
            fun property(name: String): String = Regex("(?m)^$name:[ \\t]*([^\\r\\n]*)")
                .find(block)?.groupValues?.get(1)?.trim() ?: throw AssertionError("Missing $name in $block")
            listOf(field.groupValues[1], property("Type"), property("Decimals"), property("Collation"), property("Flags"))
        }
    }

    private fun rejected(container: String, sql: String, reason: String) {
        val output = File.createTempFile("doris-replace-error-", ".log",
            File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
        try {
            val process = ProcessBuilder(listOf("docker", "exec", container) + client + sql)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Native Doris rejection probe timed out")
            }
            val text = output.readText()
            assertNotEquals(0, process.exitValue(), sql)
            assertTrue(reason in text, "$sql\n$text")
        } finally { output.delete() }
    }

    @Test
    fun liveDorisReplacementEnforcesExistingUniqueTargetsInsteadOfAppendingOrUpdatingBoth() {
        val container = container()
        val database = fixture(container)
        val cases = listOf(
            "SET missing = 7" to "Unknown column 'missing'",
            "SET id = 7, id = 8" to "Duplicate replace column name: id",
        )
        for ((stage, reason) in cases) {
            val sql = SqlFragment("FROM t |> $stage", "doris").toExecutable("doris").sql
            rejected(container, "USE $database; $sql", reason)
        }
        val ambiguous = SqlFragment("FROM t CROSS JOIN t AS u |> SET id = 7", "doris").toExecutable("doris").sql
        rejected(container, "USE $database; $ambiguous", "is ambiguous")
    }

    @Test
    fun liveDorisStarRenameIsNotSupportedDespiteNativeExceptAndReplaceSupport() {
        val container = container()
        val database = fixture(container)
        rejected(container, "USE $database; SELECT * RENAME (id AS x) FROM t", "RENAME")
        assertFailsWith<UnsupportedError> { SqlFragment("FROM t |> RENAME id AS x", "doris").toExecutable("doris") }
    }
}
