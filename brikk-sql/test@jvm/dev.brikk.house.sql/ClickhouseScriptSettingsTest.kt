package dev.brikk.house.sql

import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.dialects.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseScriptSettingsTest {
    private val division = "SELECT CAST(1 AS Int8)/CAST(0 AS Int8)"
    private fun reports(sql: String) = clickhouseScriptSemanticDiagnostics(sql, "26.10.2.103")
    private fun rules(sql: String) = reports(sql).map { it.errors.map { error -> error.rule.name } }

    @Test fun assignmentOverridesResetAndFreshCalls() {
        assertEquals(listOf(emptyList(), listOf("DIVISION_BY_ZERO")), rules("SET sql_compatible_division_for_integers=1; $division"))
        assertEquals(listOf(emptyList(), emptyList()), rules("SET sql_compatible_division_for_integers=0; $division"))
        assertTrue(reports(division).single().errors.isEmpty())
        val script = "SET sql_compatible_division_for_integers=1; $division"
        val range = script.indexOf("SELECT") until script.length
        assertTrue(clickhouseSemanticDiagnostics(script, range, "26.10.2.103").errors.isEmpty())
        assertTrue(reports("SET sql_compatible_division_for_integers=1; SET sql_compatible_division_for_integers=DEFAULT; $division").last().errors.isEmpty())
        assertTrue(reports("SET sql_compatible_division_for_integers=DEFAULT,sql_compatible_division_for_integers=1; $division").last().errors.isEmpty())
    }

    @Test fun queryLocalSettingsDoNotLeakAndUnknownOverridesShadowKnownSessionValues() {
        val script = "SET sql_compatible_division_for_integers=1; $division SETTINGS sql_compatible_division_for_integers=0; $division"
        assertEquals(listOf(emptyList(), emptyList(), listOf("DIVISION_BY_ZERO")), rules(script))
        assertTrue(reports("SET sql_compatible_division_for_integers=1; $division SETTINGS sql_compatible_division_for_integers={mode:UInt8}").last().errors.isEmpty())
        assertEquals(listOf("DIVISION_BY_ZERO"), rules("SET sql_compatible_division_for_integers=1; SET max_threads=2; $division").last())
        val nested = "SET sql_compatible_division_for_integers=1; SELECT q.v FROM (SELECT CAST(1 AS Int8)/CAST(0 AS Int8) AS v SETTINGS sql_compatible_division_for_integers=0) q"
        assertTrue(reports(nested).last().errors.isEmpty())
    }

    @Test fun unresolvedAssignmentsAndUnsupportedMutationsRecordUnknownState() {
        for (assignment in listOf("{mode:UInt8}", "DEFAULT", "2", "some_value")) {
            val result = reports("SET sql_compatible_division_for_integers=1; SET sql_compatible_division_for_integers=$assignment; $division")
            assertTrue(result.last().errors.isEmpty(), assignment)
            assertTrue(result[1].unknowns.any { it.reason == ClickhouseSemanticUnknownReason.SETTING_STATE }, assignment)
        }
        assertTrue(reports("SET sql_compatible_division_for_integers=1; SET compatibility='26.8'; $division").last().errors.isEmpty())
        assertTrue(reports("SET sql_compatible_division_for_integers=1; RESET ALL; $division").last().errors.isEmpty())
        val script = "-- 😀\nSET sql_compatible_division_for_integers=1;\n$division"
        val error = reports(script).last().errors.single().diagnostic
        assertTrue(error.start >= script.indexOf("SELECT"))
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { clickhouseScriptSemanticDiagnostics(script, "26.10.2.103") { throw stop } }.exceptionOrNull())
    }

    @Test fun nativeScriptWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val fixture = Json.parseToJsonElement(File(folder, "script-settings-cases.json").readText()).jsonObject
        val rows = fixture.getValue("execution").jsonArray.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val report = reports(sql)
            assertTrue(report.all { it.parseDiagnostics.isEmpty() }, sql)
            val rules = report.flatMap { it.errors }.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules, sql)
            val generated = Dialects.CLICKHOUSE.parse(sql).filterNotNull().joinToString("; ", postfix = ";") { it.sql("clickhouse") }
            assertEquals(rules, reports(generated).flatMap { it.errors }.map { it.rule.name }.distinct().sorted(), sql)
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql); put("generated", generated)
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseScriptSettingsOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
