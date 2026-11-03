package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Golden results are native observations, not equivalence assertions. */
class Trino484Doris415JsonEvidenceTest {
    private val data: File get() = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "vendor/data/trino-484-doris-4.1.5-json") }.first { File(it, "results.json").isFile }
    private fun fixture() = Json.parseToJsonElement(File(data, "results.json").readText()).jsonObject
    private fun vectors() = Json.parseToJsonElement(File(data, "vectors.json").readText()).jsonObject.getValue("cases").jsonArray

    private fun execute(engine: String, container: String, sql: String): Pair<Int, String> {
        val client = if (engine == "trino") listOf("trino", "--output-format=TSV", "--execute") else
            listOf("mysql", "--default-character-set=utf8mb4", "-uroot", "-h127.0.0.1", "-P9030", "-N", "-B", "-e")
        val log = File.createTempFile("paired-json-", ".log")
        try {
            val process = ProcessBuilder(listOf("docker", "exec", container) + client + sql)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Paired JSON probe timed out: $sql")
            }
            return process.exitValue() to log.readText().trimEnd('\n', '\r')
        } finally { log.delete() }
    }

    private fun assertObservation(engine: String, container: String, record: JsonObject, sql: String) {
        val expected = record.getValue("observation").jsonObject
        val (code, text) = execute(engine, container, sql)
        when (expected.getValue("status").jsonPrimitive.content) {
            "error" -> {
                assertTrue(code != 0, "$engine unexpectedly accepted $sql: $text")
                val raw = record.getValue("raw").jsonObject
                val original = raw.getValue("stderr").jsonPrimitive.content + raw.getValue("stdout").jsonPrimitive.content
                // Check the cause, not unstable query ids/line numbers, and never accept a connection error.
                val causes = listOf("Cannot convert value to JSON", "Invalid JSON", "JSON path", "json_format", "No matching function", "Invalid argument")
                val cause = causes.firstOrNull { original.contains(it, ignoreCase = true) }
                assertTrue(cause != null && text.contains(cause, ignoreCase = true), "$engine different error: $text\nwas: $original")
            }
            "sql-null" -> assertEquals(0 to "SQL_NULL", code to text, "$engine: $sql")
            "value" -> {
                val hex = expected.getValue("value").jsonPrimitive.content.toByteArray(Charsets.UTF_8)
                    .joinToString("") { "%02X".format(it.toInt() and 255) }
                assertEquals(0 to "VALUE:$hex", code to text.uppercase(), "$engine: $sql")
            }
        }
    }

    @Test
    fun recordedCaptureHasExactVersionAndVectorProvenance() {
        val root = fixture()
        val cases = root.getValue("cases").jsonArray
        assertEquals(vectors().map { it.jsonObject.getValue("id") }, cases.map { it.jsonObject.getValue("id") })
        val versions = root.getValue("versions").jsonObject
        val images = root.getValue("images").jsonObject
        for ((part, digest) in listOf(
            "trino" to "2704eb53935af5e582d63d6033e9bf99d20c325e9b0e30b1016ef59a4773979f",
            "frontend" to "e67f66e621d2d77be7d210b8cab4b1e62e89ff4a0bdaf894bf155fdb1fe441dc",
            "backend" to "3f7ff231e3a8b586b0556391f020089b666f786525ad90736bccea31761759bb",
        )) assertTrue(images.getValue(part).jsonObject.getValue("repoDigests").jsonArray.any {
            it.jsonPrimitive.content.endsWith("sha256:$digest")
        })
        assertEquals("484", versions.getValue("trino").jsonObject.getValue("stdout").jsonPrimitive.content.trim())
        for (part in listOf("frontend", "backend")) {
            assertTrue(versions.getValue(part).jsonObject.getValue("stdout").jsonPrimitive.content.contains("doris-4.1.5-release-917b20be838"))
        }
        for (row in cases.map { it.jsonObject }) {
            for (engine in listOf("trino", "doris")) {
                val record = row.getValue(engine).jsonObject
                assertTrue(record.getValue("sql").jsonPrimitive.content.startsWith("SELECT CASE WHEN"))
                val raw = record.getValue("raw").jsonObject
                if (record.getValue("observation").jsonObject.getValue("status").jsonPrimitive.content == "error") {
                    assertTrue(raw.getValue("exitCode").jsonPrimitive.int != 0)
                } else {
                    assertEquals(0, raw.getValue("exitCode").jsonPrimitive.int)
                    val observed = record.getValue("observation").jsonObject
                    val encoded = observed["value"]?.jsonPrimitive?.content?.toByteArray(Charsets.UTF_8)
                        ?.joinToString("") { "%02X".format(it.toInt() and 255) }
                    val transport = encoded?.let { "VALUE:$it" } ?: "SQL_NULL"
                    assertEquals(transport, raw.getValue("stdout").jsonPrimitive.content.trimEnd('\n', '\r').uppercase())
                }
            }
        }
    }

    @Test
    fun liveEnginesAndRegeneratedSqlReproduceEachRecordedOutcome() {
        val trino = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        val doris = System.getenv("BRIKK_DORIS_TEST_CONTAINER")
        assumeTrue(!trino.isNullOrBlank() && !doris.isNullOrBlank(), "Both native JSON probe engines must be configured")
        assertEquals(0 to "484", execute("trino", trino!!, "SELECT version()"))
        for (query in listOf("SHOW FRONTENDS", "SHOW BACKENDS")) {
            val result = execute("doris", doris!!, query)
            assertEquals(0, result.first)
            assertTrue(result.second.contains("doris-4.1.5-release-917b20be838"))
        }
        for (row in fixture().getValue("cases").jsonArray.map { it.jsonObject }) {
            for ((engine, container) in listOf("trino" to trino, "doris" to doris!!)) {
                val record = row.getValue(engine).jsonObject
                val sql = record.getValue("sql").jsonPrimitive.content
                assertObservation(engine, container, record, sql)
                // Function-lookup/path errors are tested natively; valid forms also exercise Brikk's renderer.
                if (record.getValue("observation").jsonObject.getValue("status").jsonPrimitive.content != "error") {
                    assertObservation(engine, container, record, parseOne(sql, engine).sql(engine))
                }
            }
        }
    }

    @Test
    fun actualTrinoToDorisScalarRenderingsReproduceTheMeasuredConstraints() {
        val doris = System.getenv("BRIKK_DORIS_TEST_CONTAINER")
        assumeTrue(!doris.isNullOrBlank(), "BRIKK_DORIS_TEST_CONTAINER is not configured")
        assertTrue(execute("doris", doris!!, "SHOW BACKENDS").second.contains("doris-4.1.5-release-917b20be838"))
        val ids = setOf("path-string", "path-object", "path-array", "path-missing", "path-json-null", "path-bool",
            "path-zero", "path-out-of-range", "path-nested", "scalar-decimal", "scalar-exponent",
            "typed-decimal-scalar", "typed-exponent-scalar", "typed-duplicate-scalar", "duplicate-scalar")
        val inputs = vectors().associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        var tested = 0
        for (row in fixture().getValue("cases").jsonArray.map { it.jsonObject }) {
            val id = row.getValue("id").jsonPrimitive.content
            if (id !in ids) continue
            val expression = inputs.getValue(id).getValue("trino").jsonPrimitive.content
            val generated = parseOne("SELECT $expression AS v", "trino").sql("doris")
            val sql = "SELECT CASE WHEN v IS NULL THEN 'SQL_NULL' ELSE CONCAT('VALUE:', HEX(CAST(v AS STRING))) END FROM ($generated) AS probe"
            // The expected Doris result is sometimes deliberately different from Trino.
            // Certification's warning must remain visible; generation alone is not proof.
            assertObservation("doris", doris, row.getValue("doris").jsonObject, sql)
            tested++
        }
        assertEquals(ids.size, tested)
    }
}
