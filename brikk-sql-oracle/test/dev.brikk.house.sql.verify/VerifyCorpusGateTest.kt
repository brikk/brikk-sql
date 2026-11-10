package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.fail
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gate: every SQL in brikk-sql/testResources/ast-corpus/<dialect>-serde.json is re-parsed
 * and re-generated through brikk's own dialect pipeline, and the *actual* output is fed to
 * the target engine's own parser via [SqlOracles]. A reject means the engine's native
 * grammar does not accept SQL we emit for it — a real dialect bug.
 *
 * (The corpora's `generated` strings are the Python oracle's outputs; ours equal them
 * byte-for-byte except at the registered brikk extensions — docs/brikk-extensions.md —
 * several of which exist precisely to make the emitted SQL grammar-legal, so the gate must
 * verify what brikk actually emits, not the oracle's string.)
 *
 * Rejects must exactly match testResources/<dialect>-verify-known-failures.json, in both
 * directions: unledgered rejects fail the gate, and ledger entries that now pass are stale
 * and also fail the gate. The actual reject set is always written to
 * build/ledger-actual/<dialect>-verify-ledger-actual.json for review.
 */
class VerifyCorpusGateTest {

    @Serializable
    private data class OracleCase(val sql: String, val generated: String)

    @Serializable
    private data class Corpus(val sqlglot_version: String, val cases: List<OracleCase>)

    @Serializable
    private data class LedgerCase(val sql: String, val generated: String, val error: String)

    @Serializable
    private data class Ledger(val engine: String, val cases: List<LedgerCase>)

    @Serializable
    private data class UnverifiedCase(val sql: String, val reason: String)

    @Serializable
    private data class UnverifiedCorpus(val engine: String, val cases: List<UnverifiedCase>)

    private val json = Json { ignoreUnknownKeys = true }
    private val ledgerJson = Json { prettyPrint = true }

    @Serializable
    private data class TranspileCase(val sql: String, val write: Map<String, JsonElement> = emptyMap())

    @Serializable
    private data class DialectCorpus(val transpile: List<TranspileCase>)

    @Test
    fun dorisGeneratedSqlIsAcceptedByDorisParserModuloLedger() = runGate("doris")

    /**
     * Transpile-direction gate: for every dialect-corpus/doris.json `transpile[]` case with
     * a `write.doris` entry, the case's SQL is transpiled doris->doris through brikk —
     * exactly what a "transpile AND verify" editor action would hand to the engine. Same
     * ledger contract as the serde gate, with its own ledger file.
     */
    @Test
    fun dorisTranspileOutputsAreAcceptedByDorisParserModuloLedger() {
        val engine = "doris"
        val corpus = json.decodeFromString(
            DialectCorpus.serializer(),
            corpusResource("dialect-corpus/doris.json"),
        )
        var unsupportedExpectations = 0
        val sqls = corpus.transpile.mapNotNull { case ->
            when (val expectation = case.write[engine]) {
                null -> null
                is JsonPrimitive -> {
                    check(expectation.isString) { "Invalid $engine SQL expectation" }
                    case.sql
                }
                is JsonObject -> {
                    check(expectation.keys == setOf("error") && expectation["error"]?.jsonPrimitive?.content == "UnsupportedError")
                    unsupportedExpectations++
                    null // No emitted SQL; the core transpile gate checks the generation refusal.
                }
                else -> error("Invalid $engine expectation")
            }
        }
        println("VerifyCorpusGateTest[doris-transpile]: $unsupportedExpectations non-SQL generation-refusal expectations handled by the transpile gate")
        check(sqls.isNotEmpty()) { "no doris transpile outputs in dialect corpus" }
        val ledger = json.decodeFromString(
            Ledger.serializer(),
            ledgerResource("$engine-transpile-verify-known-failures.json"),
        )
        runGateOver(engine, "doris-transpile", sqls, ledger)
    }

    private fun verifierFor(engine: String): SqlVerifier =
        SqlOracles.forEngine(engine) ?: fail("no verifier available for engine $engine")

    @Test
    fun trinoGeneratedSqlIsAcceptedByTrinoParserModuloLedger() = runGate("trino")

    @Test
    fun duckdbGeneratedSqlIsAcceptedByDuckdbParserModuloLedger() = runGate("duckdb")

    @Test
    fun explicitUnverifiedCasesNeverWaiveUnavailableUnexpectedOrStaleOutcomes() {
        fun verifier(result: VerifyResult) = object : SqlVerifier {
            override val engine = "duckdb"
            override fun verify(sql: String): VerifyResult = if (sql == "SELECT 1") VerifyResult.Accepted else result
            override fun verifyExpression(sql: String): VerifyResult = result
        }
        val empty = Ledger("duckdb", emptyList())
        val unexpected = assertFailsWith<AssertionError> {
            runGateWith("duckdb", "unexpected-control", listOf("SELECT 42"), empty, verifier(VerifyResult.Unverified("control")))
        }
        assertContains(unexpected.message.orEmpty(), "case 0")
        val unavailable = assertFailsWith<AssertionError> {
            runGateWith("duckdb", "unavailable-control", listOf("INSTALL httpfs"), empty, verifier(VerifyResult.Unavailable("control")))
        }
        assertContains(unavailable.message.orEmpty(), "case 0")
        val forms = json.decodeFromString(UnverifiedCorpus.serializer(), ledgerResource("duckdb-verify-unverified.json")).cases.map { it.sql }
        val stale = assertFailsWith<AssertionError> {
            runGateWith("duckdb", "stale-control", forms, empty, verifier(VerifyResult.Accepted))
        }
        assertContains(stale.message.orEmpty(), "STALE unverified classifications")
    }

    @Test
    fun anExistingRejectKeyCannotWaiveAChangedOutputOrError() {
        val verifier = object : SqlVerifier {
            override val engine = "duckdb"
            override fun verify(sql: String): VerifyResult = if (sql == "SELECT 1") VerifyResult.Accepted else VerifyResult.Rejected(VerifyError("synthetic reject"))
            override fun verifyExpression(sql: String): VerifyResult = VerifyResult.Rejected(VerifyError("synthetic reject"))
        }
        val changed = assertFailsWith<AssertionError> {
            runGateWith("duckdb", "changed-control", listOf("SELECT 42"),
                Ledger("duckdb", listOf(LedgerCase("SELECT 42", "SELECT 0", "old reject"))), verifier)
        }
        assertContains(changed.message.orEmpty(), "CHANGED reject signatures")
    }

    /**
     * Postgres gate: re-generates postgres-serde.json through brikk's postgres pipeline and
     * feeds each output to a REAL embedded PostgreSQL, which discriminates grammar rejection
     * from catalog/semantic failure by SQLSTATE (see [PostgresVerifier]'s KDoc). Rejects here
     * are genuine dialect bugs OR a SQLSTATE we mis-partitioned; both are ledgered with reasons.
     *
     * [SqlOracles.forEngine] routes `"postgres"` to the fidelity embedded PostgreSQL oracle
     * (whereas [SqlVerifiers.forEngine] would return the advisory ShardingSphere verifier), so
     * this gate needs no special-case verifier.
     */
    @Test
    fun postgresGeneratedSqlIsAcceptedByPostgresParserModuloLedger() = runGate("postgres")

    private fun runGate(engine: String) {
        val corpus = json.decodeFromString(
            Corpus.serializer(),
            corpusResource("ast-corpus/$engine-serde.json"),
        )
        val ledger = json.decodeFromString(
            Ledger.serializer(),
            ledgerResource("$engine-verify-known-failures.json"),
        )
        check(corpus.cases.isNotEmpty()) { "empty $engine corpus" }
        runGateOver(engine, engine, corpus.cases.map { it.sql }, ledger)
    }

    /**
     * Each of [sqls] is re-generated through brikk's own dialect pipeline
     * (parse under [engine], generate under [engine]) and the result is verified against
     * the engine's native parser; the ledger is keyed by source sql.
     */
    private fun runGateOver(
        engine: String,
        label: String,
        sqls: List<String>,
        ledger: Ledger,
    ) {
        // SqlOracles routes postgres -> embedded PG and clickhouse -> chDB; trino/doris/duckdb
        // reuse the lightweight JVM parsers. Close it afterwards to stop any embedded engine.
        val verifier = verifierFor(engine)
        try {
            runGateWith(engine, label, sqls, ledger, verifier)
        } finally {
            runCatching { verifier.close() }
        }
    }

    private fun runGateWith(
        engine: String,
        label: String,
        sqls: List<String>,
        ledger: Ledger,
        verifier: SqlVerifier,
    ) {
        check(ledger.engine == engine) { "ledger engine mismatch: ${ledger.engine} != $engine" }

        val preflight = verifier.verify("SELECT 1")
        if (!preflight.isVerdict) {
            println("VerifyCorpusGateTest[$label]: skipped ${sqls.size} cases; ${summarize(preflight)}")
            return
        }

        val dialect = Dialects.forName(engine)
        val ledgered = ledger.cases.associateBy { it.sql }
        val unverifiedCases = if (engine == "duckdb") json.decodeFromString(
            UnverifiedCorpus.serializer(), ledgerResource("duckdb-verify-unverified.json"),
        ).also { check(it.engine == engine) }.cases else emptyList()
        val expectedUnverified = unverifiedCases.associateBy { it.sql }
        check(expectedUnverified.size == unverifiedCases.size && unverifiedCases.all { it.reason.isNotBlank() })
        check(expectedUnverified.keys.intersect(ledgered.keys).isEmpty()) { "Unverified classifications cannot waive reject-ledger entries" }
        val unverified = LinkedHashMap<String, Pair<String, String>>()
        // sql -> (generated, engine error)
        val failures = LinkedHashMap<String, Pair<String, String>>()
        var accepted = 0

        for ((index, sourceSql) in sqls.withIndex()) {
            val generated = try {
                dialect.generate(dialect.parseOne(sourceSql))
            } catch (e: Exception) {
                failures[sourceSql] = "" to
                    "brikk parse/generate failed: ${e::class.simpleName}: ${e.message?.take(140)}"
                continue
            }
            // The corpora mix full statements with bare-expression fixtures (e.g.
            // `DAYNAME(x)`), so try the engine's statement grammar first and fall back to its
            // expression grammar. A reject means neither native entry point accepts the SQL.
            val asStatement = verifier.verify(generated)
            if (!asStatement.isVerdict) {
                if (asStatement is VerifyResult.Unverified && sourceSql in expectedUnverified) {
                    unverified[sourceSql] = generated to summarize(asStatement)
                    continue
                }
                fail("$engine verifier became unavailable during $label case $index: ${summarize(asStatement)}")
            }
            val result = if (asStatement is VerifyResult.Accepted) {
                asStatement
            } else {
                verifier.verifyExpression(generated)
            }
            if (!result.isVerdict) {
                fail("$engine expression verifier became unavailable during $label case $index: ${summarize(result)}")
            }
            if (result is VerifyResult.Accepted) {
                accepted += 1
            } else {
                val reason = "statement: ${summarize(asStatement)} | expression: ${summarize(result)}"
                failures[sourceSql] = generated to reason
            }
        }

        // Always write the actual reject set in ledger format for easy regeneration.
        val actualLedger = buildJsonObject {
            put("engine", engine)
            put("cases", buildJsonArray {
                for ((sql, generatedAndError) in failures) {
                    add(buildJsonObject {
                        put("sql", sql)
                        put("generated", generatedAndError.first)
                        put("error", generatedAndError.second)
                    })
                }
            })
        }
        val actualFile = oracleLedgerActualFile("$label-verify-ledger-actual.json")
        actualFile.writeText(ledgerJson.encodeToString(JsonObject.serializer(), actualLedger))
        if (engine == "duckdb") oracleLedgerActualFile("$label-unverified-ledger-actual.json").writeText(
            ledgerJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("engine", engine)
                put("cases", buildJsonArray { for ((sql, outcome) in unverified) add(buildJsonObject {
                    put("sql", sql); put("generated", outcome.first); put("status", "unverified"); put("reason", outcome.second)
                }) })
            }),
        )

        val unledgered = failures.keys - ledgered.keys
        val stale = ledgered.keys - failures.keys
        val changed = (ledgered.keys intersect failures.keys).filter { sql ->
            val expected = ledgered.getValue(sql)
            val actual = failures.getValue(sql)
            expected.generated != actual.first || expected.error != actual.second
        }
        val staleUnverified = expectedUnverified.keys - unverified.keys

        println("VerifyCorpusGateTest[$label]: $accepted accepted / ${failures.size} ledgered / ${unverified.size} explicitly unverified (of ${sqls.size})")

        val problems = mutableListOf<String>()
        if (staleUnverified.isNotEmpty()) problems.add("STALE unverified classifications: ${staleUnverified.joinToString()}")
        if (unledgered.isNotEmpty()) {
            problems.add(
                "${unledgered.size} UNLEDGERED rejects (showing up to 20):\n" +
                    unledgered.take(20).joinToString("\n") {
                        "  $it\n    generated: ${failures[it]?.first}\n    error: ${failures[it]?.second}"
                    }
            )
        }
        if (stale.isNotEmpty()) {
            problems.add(
                "${stale.size} STALE ledger entries now pass (showing up to 20):\n" +
                    stale.take(20).joinToString("\n") { "  $it" }
            )
        }
        if (changed.isNotEmpty()) problems.add("CHANGED reject signatures: ${changed.joinToString()}")
        if (problems.isNotEmpty()) {
            fail(
                problems.joinToString("\n\n") +
                    "\n\nActual ledger written to ${actualFile.absolutePath}"
            )
        }
    }

    /** Engine error message trimmed for the ledger (Doris "expecting {...}" lists run to kilobytes). */
    private fun summarize(result: VerifyResult): String {
        val message = when (result) {
            VerifyResult.Accepted -> "accepted"
            is VerifyResult.Rejected -> result.error.message
            is VerifyResult.Unverified -> result.reason
            is VerifyResult.Unavailable -> result.reason
        }.replace("\n", " ")
        val cut = message.take(160).let { if (message.length > 160) "$it…" else it }
        val error = (result as? VerifyResult.Rejected)?.error
        val position = error?.line?.let { " (line $it, col ${error.column})" } ?: ""
        return cut + position
    }

    private val VerifyResult.isVerdict: Boolean
        get() = this is VerifyResult.Accepted || this is VerifyResult.Rejected

    /** Cross-module corpus files, read from the filesystem relative to repo root or module dir. */
    private fun corpusResource(path: String): String {
        val file = java.io.File(oracleProjectRoot(), "brikk-sql/testResources/$path")
        if (!file.isFile) fail("corpus $path not found at ${file.absolutePath}")
        return file.readText()
    }

    private fun ledgerResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: java.io.File(oracleProjectRoot(), "brikk-sql-oracle/testResources/$path")
                .takeIf { it.isFile }?.inputStream()
            ?: fail("ledger $path not found on classpath or filesystem")
        return stream.use { it.readBytes().decodeToString() }
    }
}

private const val LEDGER_OUT_PROPERTY = "brikk.ledgerOut"

private fun oracleProjectRoot(): java.io.File {
    val starts = listOf(
        java.io.File(VerifyCorpusGateTest::class.java.protectionDomain.codeSource.location.toURI()),
        java.io.File("").absoluteFile,
    )
    for (candidate in starts) {
        val directory = if (candidate.isFile) candidate.parentFile else candidate
        generateSequence(directory.canonicalFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "project.yaml").isFile }
            ?.let { return it }
    }
    error("cannot locate project root from ${starts.map { it.absolutePath }}")
}

private fun oracleLedgerActualFile(name: String): java.io.File {
    require(name == java.io.File(name).name && name.endsWith("-ledger-actual.json"))
    val root = oracleProjectRoot()
    val configured = System.getProperty(LEDGER_OUT_PROPERTY)
    require(configured == null || configured.isNotBlank()) { "-$LEDGER_OUT_PROPERTY must not be blank" }
    val requested = configured?.let { java.io.File(it) } ?: java.io.File(root, "build/ledger-actual")
    val directory = (if (requested.isAbsolute) requested else java.io.File(root, configured!!)).canonicalFile
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        error("cannot create ledger output directory ${directory.absolutePath}")
    }
    return java.io.File(directory, name)
}
