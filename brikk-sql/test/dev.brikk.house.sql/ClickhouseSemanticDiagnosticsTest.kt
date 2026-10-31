package dev.brikk.house.sql

import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.*

class ClickhouseSemanticDiagnosticsTest {
    private val version = "26.10.2.103"
    private fun analyze(sql: String) = clickhouseSemanticDiagnostics(sql, sql.indices, version)
    private fun text(sql: String, error: ClickhouseSemanticError) = sql.substring(error.diagnostic.start, error.diagnostic.end)

    @Test
    fun bareVariadicErrorMarksItsDesignatorAndExplicitCallRemainsDistinct() {
        val sql = "SELECT arrayMap(trim | upper,[' x '])"
        val report = analyze(sql)
        assertTrue(report.parseDiagnostics.isEmpty())
        val error = report.errors.single()
        assertEquals(ClickhouseSemanticRule.VARIADIC_LEFT_DESIGNATOR, error.rule)
        assertEquals(36, error.nativeErrorCode)
        assertEquals("trim", text(sql, error))
        assertEquals(DiagnosticSource.OFFLINE_SEMANTIC, error.diagnostic.source)
        assertEquals(DiagnosticSeverity.ERROR, error.diagnostic.severity)
        assertTrue(error.diagnostic.message.contains("placeholder"))
        assertTrue(analyze("SELECT arrayMap(trim(_) | upper,[' x '])").errors.isEmpty())
    }

    @Test
    fun scriptOffsetsIncludeTriviaCrLfSurrogatePairsAndRepeatedDesignators() {
        val script = "-- 𝄞 trim\r\nSELECT 'trim;𝄞';\r\n/* trim */ SELECT arrayMap(trim | upper,['trim']);\r\nSELECT arrayMap(trim | upper,['trim']); -- done"
        val reports = clickhouseScriptSemanticDiagnostics(script, version)
        assertEquals(3, reports.size)
        assertTrue(reports.first().errors.isEmpty())
        val starts = reports.flatMap { it.errors }.map { it.diagnostic.start }
        val first = script.indexOf("trim |")
        assertEquals(listOf(first, script.indexOf("trim |", first + 1)), starts)
        assertTrue(reports.flatMap { it.errors }.all { text(script, it) == "trim" })
        for (report in reports) {
            assertTrue(report.errors.all { it.diagnostic.start >= report.start && it.diagnostic.end <= report.end })
            assertTrue(report.unknowns.all { it.start >= report.start && it.end <= report.end && it.end > it.start })
        }
        val range = reports[1].start until reports[1].end
        assertEquals(reports[1], clickhouseSemanticDiagnostics(script, range, version))
    }

    @Test
    fun repeatedErrorsInOneStatementKeepTheirOwnOrderedRanges() {
        val sql = "SELECT arrayMap(trim | upper,['x']), arrayMap(trim | upper,['x'])"
        val report = analyze(sql)
        assertEquals(report, analyze(sql))
        val errors = report.errors
        assertEquals(2, errors.size)
        assertEquals(listOf(sql.indexOf("trim |"), sql.lastIndexOf("trim |")), errors.map { it.diagnostic.start })
        assertTrue(errors.all { text(sql, it) == "trim" })
    }

    @Test
    fun explicitCountsTypesAndPlaceholderErrorsHaveNonEmptyPositions() {
        val cases = listOf(
            "SELECT arrayMap(lower | multiply,['x'])" to ClickhouseSemanticRule.RIGHT_OPERAND_ARITY,
            "SELECT arrayMap(lower | upper,[1])" to ClickhouseSemanticRule.INPUT_TYPE,
            "SELECT arrayMap(_ + _1,[1])" to ClickhouseSemanticRule.MIXED_PLACEHOLDERS,
            "SELECT arrayMap(_129,[1])" to ClickhouseSemanticRule.PLACEHOLDER_LIMIT,
            "SELECT arrayMap(upper(trim(_)),['x'])" to ClickhouseSemanticRule.ANONYMOUS_DEPTH,
            "SELECT arrayMap(x -> 'constant',1)" to ClickhouseSemanticRule.ARRAY_INPUT_TYPE,
            "SELECT arrayMap((x,y) -> x,[1])" to ClickhouseSemanticRule.ARRAY_MAP_ARITY,
        )
        for ((sql, rule) in cases) {
            val report = analyze(sql)
            assertTrue(report.parseDiagnostics.isEmpty(), sql)
            val error = report.errors.first { it.rule == rule }
            assertTrue(text(sql, error).isNotBlank(), "$rule: $error")
            assertTrue(error.diagnostic.start in sql.indices && error.diagnostic.end <= sql.length)
        }
        val nested = "SELECT arrayMap(upper(trim(_)),['x'])"
        assertEquals("_", text(nested, analyze(nested).errors.first { it.rule == ClickhouseSemanticRule.ANONYMOUS_DEPTH }))
    }

    @Test
    fun unavailableKnowledgeIsStructuredDataRatherThanAnErrorSquiggle() {
        for ((sql, reason) in listOf(
            "SELECT arrayMap(my_udf | upper,['x'])" to ClickhouseSemanticUnknownReason.UNRESEARCHED_CALLABLE,
            "SELECT arrayMap(lower | upper,t.a) FROM t" to ClickhouseSemanticUnknownReason.NAME_RESOLUTION,
            "SELECT arrayMap(toString(_),CAST([NULL] AS Array(Nullable(UInt8))))" to ClickhouseSemanticUnknownReason.NULL_OR_WRAPPER,
            "SELECT arrayMap(multiply,CAST([1,2] AS Array(UInt8)),CAST([3] AS Array(UInt8)))" to ClickhouseSemanticUnknownReason.ARRAY_LENGTHS,
            "SELECT arrayMap(domainWithoutWWW(_) | upper,CAST([] AS Array(FixedString(3))))" to ClickhouseSemanticUnknownReason.RUNTIME_RESTRICTION,
            "SELECT arrayMap((x -> toString(x)) | (y -> upper(y)),[1])" to ClickhouseSemanticUnknownReason.PARSER_GAP,
        )) {
            val report = analyze(sql)
            assertTrue(report.errors.isEmpty(), sql)
            assertTrue(report.unknowns.any { it.reason == reason }, "$sql: ${report.unknowns}")
        }
        for (pin in listOf("26.8.34.13", "26.10", "27.1.1", "")) {
            val sql = "SELECT arrayMap(trim | upper,['x'])"
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, pin)
            assertTrue(report.errors.isEmpty())
            assertEquals(listOf(ClickhouseSemanticUnknownReason.ENGINE_VERSION), report.unknowns.map { it.reason })
        }
    }

    @Test
    fun suppliedSchemaCanProveIncompatibilityWithoutAssumingMissingFacts() {
        val sql = "SELECT arrayMap(lower | upper,t.a) FROM t"
        val unknown = analyze(sql)
        assertTrue(unknown.errors.isEmpty())
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Array(UInt8)")), dialect = Dialects.CLICKHOUSE)
        val known = clickhouseSemanticDiagnostics(sql, sql.indices, version, schema)
        assertTrue(known.errors.any { it.rule == ClickhouseSemanticRule.INPUT_TYPE })
    }

    @Test
    fun parserAndBoundaryFailuresDoNotYieldSemanticErrorsOrAValidityFlag() {
        for (sql in listOf("SELECT arrayMap(trim | upper,['x']) WHERE = = 1", "SELECT 'unclosed", "SELECT arrayMap(")) {
            val report = analyze(sql)
            assertTrue(report.parseDiagnostics.isNotEmpty(), sql)
            assertTrue(report.parseDiagnostics.all { it.source == DiagnosticSource.LOCAL_PARSER })
            assertTrue(report.errors.isEmpty(), sql)
        }
        val multi = analyze("SELECT arrayMap(trim | upper,['x']); SELECT 2")
        assertTrue(multi.errors.isEmpty())
        assertEquals(listOf(ClickhouseSemanticUnknownReason.STATEMENT_BOUNDARY), multi.unknowns.map { it.reason })
        assertTrue(clickhouseScriptSemanticDiagnostics(" -- only\n/* trivia */", version).isEmpty())
        assertTrue(clickhouseScriptSemanticDiagnostics("", version).isEmpty())
        assertFailsWith<IllegalArgumentException> { clickhouseSemanticDiagnostics("", 0..0, version) }
        assertFailsWith<IllegalArgumentException> { clickhouseSemanticDiagnostics("SELECT 1", -1..4, version) }
    }

    @Test
    fun insertPayloadIsOpaqueAndDoesNotHideTheNextStatement() {
        val sql = "INSERT INTO t FORMAT CSV\narrayMap(trim | upper,['x'])\n;\nSELECT arrayMap(trim | upper,['x'])"
        val reports = clickhouseScriptSemanticDiagnostics(sql, version)
        assertEquals(2, reports.size)
        assertTrue(reports.first().errors.isEmpty())
        assertEquals(sql.lastIndexOf("trim |"), reports.last().errors.single().diagnostic.start)
    }

    @Test
    fun nativeParserAcceptanceCannotSuppressSemanticEvidence() {
        val sql = "SELECT arrayMap(trim | upper,['x'])"
        val report = analyze(sql)
        val semantic = report.errors.map { it.diagnostic }
        val parser = SqlDiagnostic(DiagnosticSeverity.ERROR, "parser", 0, 6, DiagnosticSource.EDITOR_PARSER)
        val accepted = combineStatementDiagnostics(sql, sql.indices, listOf(parser) + semantic, NativeOutcome.Accepted)
        assertEquals(semantic, accepted.diagnostics)
        assertTrue(accepted.nativelyAccepted) // Parser acceptance only, even with a semantic error.
        val rejected = combineStatementDiagnostics(sql, sql.indices, semantic,
            NativeOutcome.Rejected(listOf(NativeError("syntax", offset = sql.lastIndex))))
        assertTrue(rejected.diagnostics.containsAll(semantic))
        val unavailable = combineStatementDiagnostics(sql, sql.indices, semantic, NativeOutcome.NoVerdict("offline"))
        assertEquals(semantic, unavailable.diagnostics)
        assertFalse(unavailable.nativelyAccepted)
        val advisory = combineStatementDiagnostics(sql, sql.indices, semantic, NativeOutcome.Accepted, advisory = true)
        assertEquals(semantic, advisory.diagnostics)
        assertFalse(advisory.nativelyAccepted)
    }

    @Test
    fun cancellationPropagatesFromStatementAndScriptAnalysis() {
        val stop = IllegalStateException("cancel")
        val sql = "SELECT arrayMap(trim | upper,['x'])"
        assertSame(stop, runCatching { clickhouseSemanticDiagnostics(sql, sql.indices, version) { throw stop } }.exceptionOrNull())
        assertSame(stop, runCatching { clickhouseScriptSemanticDiagnostics(sql, version) { throw stop } }.exceptionOrNull())
        var checks = 0
        assertSame(stop, runCatching {
            clickhouseSemanticDiagnostics(sql, sql.indices, version) { if (++checks == 8) throw stop }
        }.exceptionOrNull())
        assertEquals(8, checks)
    }
}
