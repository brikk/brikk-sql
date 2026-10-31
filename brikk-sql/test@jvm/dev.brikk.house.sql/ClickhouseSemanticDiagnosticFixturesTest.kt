package dev.brikk.house.sql

import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.optimizer.ClickhouseCompositionRule
import dev.brikk.house.sql.optimizer.ClickhouseCompositionUnknownReason
import dev.brikk.house.sql.parser.ParseError
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame

/** Reuse the unchanged native-backed rule corpus; this round exposes it, not new semantic rules. */
class ClickhouseSemanticDiagnosticFixturesTest {
    @Test
    fun aCancellationUsingTheParseExceptionTypeIsNotMistakenForAParserFinding() {
        val sql = "SELECT arrayMap(trim | upper,['x'])"
        val stop = ParseError("cancel during parsing")
        val failure = runCatching {
            clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103") {
                if (Throwable().stackTrace.any { it.className == "dev.brikk.house.sql.parser.Parser" }) throw stop
            }
        }.exceptionOrNull()
        assertSame(stop, failure)
    }

    @Test
    fun publicRulesAndUnknownReasonsCoverEveryInternalCategory() {
        assertEquals(ClickhouseCompositionRule.entries.map { it.name }, ClickhouseSemanticRule.entries.map { it.name })
        assertEquals(ClickhouseCompositionUnknownReason.entries.map { it.name },
            ClickhouseSemanticUnknownReason.entries.filter { it != ClickhouseSemanticUnknownReason.STATEMENT_BOUNDARY }.map { it.name })
    }

    @Test
    fun everyPinnedRuleAndUnknownIsExposedWithStatementAndScriptPositions() {
        val input = File(projectRoot(), "vendor/data/clickhouse-semantic/composition-cases.json")
        val fixture = Json.parseToJsonElement(input.readText()).jsonObject
        val refinements = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/overload-refinements.json").readText()).jsonObject
            .getValue("rules").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        val historical = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/composition-brikk.json").readText())
            .jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        val prefix = "SELECT '𝄞;';\r\n/* trim | upper */\r\n"
        val rows = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val sql = case.getValue("sql").jsonPrimitive.content
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
            val inScript = clickhouseScriptSemanticDiagnostics(prefix + sql + "; -- end", "26.10.2.103").last()
            assertTrue(report.parseDiagnostics.isEmpty(), id)
            assertTrue(inScript.parseDiagnostics.isEmpty(), id)
            val rules = report.errors.map { it.rule.name }.distinct().sorted()
            val reasons = report.unknowns.map { it.reason.name }.distinct().sorted()
            assertEquals((refinements[id]?.get("rules") ?: case.getValue("rules")).jsonArray.map { it.jsonPrimitive.content }.sorted(), rules, id)
            assertEquals((refinements[id]?.get("unknown_reasons") ?: historical.getValue(id).getValue("unknown_reasons")).jsonArray.map { it.jsonPrimitive.content }, reasons, id)
            assertEquals(report.errors.map { it.rule to it.nativeErrorCode }, inScript.errors.map { it.rule to it.nativeErrorCode }, id)
            for ((original, shifted) in report.errors.zip(inScript.errors)) {
                assertEquals(original.diagnostic.start + prefix.length, shifted.diagnostic.start, id)
                assertEquals(original.diagnostic.end + prefix.length, shifted.diagnostic.end, id)
                assertEquals(original.diagnostic.message, shifted.diagnostic.message, id)
                assertEquals(DiagnosticSource.OFFLINE_SEMANTIC, original.diagnostic.source)
                assertEquals(DiagnosticSeverity.ERROR, original.diagnostic.severity)
            }
            assertEquals(report.unknowns.map { it.copy(start = it.start + prefix.length, end = it.end + prefix.length) }, inScript.unknowns, id)
            for (unknown in report.unknowns) assertTrue(unknown.start >= 0 && unknown.end <= sql.length && unknown.end > unknown.start, id)
            val old = clickhouseSemanticDiagnostics(sql, sql.indices, "26.8.34.13")
            assertTrue(old.errors.isEmpty(), id)
            assertEquals(listOf(ClickhouseSemanticUnknownReason.ENGINE_VERSION), old.unknowns.map { it.reason }, id)
            buildJsonObject {
                put("id", id)
                put("input_sql", sql)
                put("engine_version", "26.10.2.103")
                put("errors", JsonArray(report.errors.map { error ->
                    val diagnostic = error.diagnostic
                    buildJsonObject {
                        put("rule", error.rule.name)
                        put("native_error_code", error.nativeErrorCode)
                        put("message", diagnostic.message)
                        put("source", diagnostic.source.name)
                        put("severity", diagnostic.severity.name)
                        put("start", diagnostic.start)
                        put("end", diagnostic.end)
                        put("text", sql.substring(diagnostic.start, diagnostic.end))
                    }
                }))
                put("unknowns", JsonArray(report.unknowns.map { unknown -> buildJsonObject {
                    put("reason", unknown.reason.name)
                    put("start", unknown.start)
                    put("end", unknown.end)
                    put("text", sql.substring(unknown.start, unknown.end))
                } }))
            }
        }
        System.getProperty("brikk.clickhouseDiagnosticsOut")?.let { path ->
            File(path).apply { parentFile?.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
        }
    }
}
