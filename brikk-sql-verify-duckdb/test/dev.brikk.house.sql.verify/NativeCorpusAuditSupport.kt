package dev.brikk.house.sql.verify

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.duckdb.DuckDBDriver
import kotlin.test.*

/** Separate JVMs are mandatory even when both JDBC jars are already on disk. */
internal object NativeCorpusAudit {
    val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("project.yaml")) }
    val resources: Path = root.resolve("brikk-sql/testResources")
    val output: Path = root.resolve("build/duckdb-parser-audit").also(Files::createDirectories)
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun json(path: String): JsonObject = Json.parseToJsonElement(Files.readString(resources.resolve(path))).jsonObject
    fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun report(name: String, value: JsonElement) { Files.writeString(output.resolve(name), Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), value)) }
    data class Input(val origin: String, val sql: String)
    data class Verdict(val status: String, val type: String, val message: String)
    data class Run(val engine: List<String>, val settings: JsonArray, val jarHash: String, val verdicts: Map<String, Verdict>)
    fun currentJar(): Path = Path.of(DuckDBDriver::class.java.protectionDomain.codeSource.location.toURI())
    fun probe(name: String, jar: Path, inputs: List<Input>, version: String, source: String): Run {
        val directory = Files.createTempDirectory(output, "$name-")
        val input = directory.resolve("input.tsv")
        val result = directory.resolve("result.tsv")
        val log = directory.resolve("process.log")
        val unique = inputs.map { it.sql }.distinct()
        fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        fun decode(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        Files.write(input, unique.mapIndexed { index, sql -> "$index\t${encode(sql)}" })
        val helper = root.resolve("brikk-sql-verify-duckdb/testResources/NativeParserProbe.java")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp", jar.toString(), helper.toString(), input.toString(), result.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
        assertTrue(finished, "$name child exceeded deadline; no grammar verdict: $log")
        assertEquals(0, process.exitValue(), "$name child failed; no grammar verdict: ${Files.readString(log)}")
        val lines = Files.readAllLines(result).map { it.split('\t') }
        assertEquals("ENGINE", lines[0][0])
        val engine = lines[0].drop(1).map(::decode)
        assertEquals(version, engine[0])
        assertEquals(source, engine[1])
        val settings = Json.parseToJsonElement(decode(lines[1][1])).jsonArray
        val values = settings.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject["value"]?.jsonPrimitive?.contentOrNull }
        for (key in listOf("enable_external_access", "autoload_known_extensions", "autoinstall_known_extensions")) assertEquals("false", values[key], key)
        assertEquals("true", values["lock_configuration"])
        val rows = lines.drop(2)
        assertEquals(unique.size, rows.size, "missing native outcomes")
        val verdicts = rows.mapIndexed { index, row ->
            assertEquals(index.toString(), row[0])
            assertTrue(row[1] in setOf("accepted", "rejected", "unverified", "unavailable"))
            unique[index] to Verdict(row[1], decode(row[2]), decode(row[3]))
        }.toMap()
        return Run(engine, settings, hash(Files.readAllBytes(jar)), verdicts)
    }
    fun metadata(run: Run): JsonObject = buildJsonObject {
        put("engine", JsonArray(run.engine.map(::JsonPrimitive)))
        put("jdbc_sha256", run.jarHash)
        put("settings", run.settings)
    }
    fun counts(inputs: List<Input>, run: Run): JsonObject = buildJsonObject {
        inputs.groupingBy { run.verdicts.getValue(it.sql).status }.eachCount().toSortedMap().forEach { (key, value) -> put(key, value) }
    }
    fun fingerprint(inputs: List<Input>, run: Run): String = hash(inputs.joinToString("\n") {
        "${it.origin}\t${hash(it.sql.toByteArray())}\t${run.verdicts.getValue(it.sql).status}\t${run.verdicts.getValue(it.sql).type}"
    }.toByteArray())
    fun acceptanceInputs(): List<Input> = buildList {
        for (file in listOf("parser-corpus/base.json", "ast-corpus/identity-serde.json", "ast-corpus/duckdb-serde.json", "token-corpus/duckdb.json")) {
            for ((index, case) in json(file).getValue("cases").jsonArray.withIndex()) {
                val obj = case.jsonObject
                for (field in listOf("sql", "generated")) text(obj[field])?.let { add(Input("$file/$index/$field", it)) }
            }
        }
        val file = "dialect-corpus/duckdb.json"
        for ((index, case) in json(file).getValue("identity").jsonArray.withIndex()) for (field in listOf("sql", "expected"))
            text(case.jsonObject[field])?.let { add(Input("$file/identity/$index/$field", it)) }
    }
}
