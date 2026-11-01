package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.optimizer.Schema
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseDecimalDivisionDiagnosticsTest {
    private fun report(sql: String, schema: Schema? = null) = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103", schema)
    private fun rules(sql: String) = report(sql).errors.map { it.rule.name }

    @Test fun zeroScaleAndScaleOverflowHaveDistinctCategories() {
        assertEquals(listOf("DIVISION_BY_ZERO"), rules("SELECT CAST(7 AS Decimal(12,3))/CAST(0 AS Int8)"))
        assertEquals(listOf("DIVISION_BY_ZERO"), rules("SELECT CAST('7.25' AS Decimal(12,3))/CAST('-0.000' AS Decimal(12,3))"))
        assertEquals(listOf("DECIMAL_DIVISION_SCALE"), rules("SELECT CAST(7 AS Decimal(5,2))/CAST(2 AS Decimal(12,3))"))
        assertEquals(listOf("DECIMAL_DIVISION_SCALE_OVERFLOW"), rules("SELECT CAST(0 AS Decimal32(6))/CAST(0 AS Decimal32(4)) SETTINGS decimal_check_overflow=1"))
        assertEquals(listOf("DIVISION_BY_ZERO"), rules("SELECT CAST(0 AS Decimal32(6))/CAST(0 AS Decimal32(4)) SETTINGS decimal_check_overflow=0"))
        assertTrue(rules("SELECT CAST(0 AS Decimal32(6))/CAST(0 AS Decimal32(4))").isEmpty())
    }

    @Test fun nullAndPrecedingOverflowGuardsDoNotBecomeFalseZeroFindings() {
        for (expression in listOf("CAST(NULL AS Nullable(Decimal(12,3)))/CAST(0 AS Int8)",
            "CAST(7 AS Decimal(12,3))/CAST(NULL AS Nullable(Int8))",
            "CAST(NULL AS Nullable(Decimal(5,2)))/CAST(2 AS Decimal(12,3))",
            "CAST('bad' AS Decimal(5,2))/CAST(2 AS Decimal(12,3))",
            "CAST('0.0001' AS Decimal32(3))/CAST(0 AS Int8)",
            "CAST(256 AS UInt8)/CAST(0 AS Decimal32(0))")) {
            assertTrue(rules("SELECT $expression SETTINGS decimal_check_overflow=1").isEmpty(), expression)
        }
        assertEquals(listOf("DECIMAL_DIVISION_SCALE_OVERFLOW"), rules("SELECT CAST(NULL AS Nullable(Decimal32(6)))/CAST(0 AS Decimal32(4)) SETTINGS decimal_check_overflow=1"))
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Nullable(Decimal(12,3))")), dialect = Dialects.CLICKHOUSE)
        assertTrue(report("SELECT t.a/CAST(0 AS Int8) FROM t", schema).errors.isEmpty())
        val plain = MappingSchema(mapOf("t" to mapOf("a" to "Decimal(5,2)", "b" to "Decimal(12,3)")), dialect = Dialects.CLICKHOUSE)
        assertEquals(ClickhouseSemanticRule.DECIMAL_DIVISION_SCALE,
            report("SELECT t.a/t.b FROM t", plain).errors.single().rule)
    }

    @Test fun scriptOverridesPositionsReadOnlyAndConditionalTypes() {
        val expression = "CAST(0 AS Decimal32(6))/CAST(0 AS Decimal32(4))"
        val script = "-- 😀\nSET decimal_check_overflow=1; SELECT $expression; SELECT $expression SETTINGS decimal_check_overflow=0; SELECT $expression"
        val reports = clickhouseScriptSemanticDiagnostics(script, "26.10.2.103")
        assertEquals(listOf(emptyList(), listOf("DECIMAL_DIVISION_SCALE_OVERFLOW"), listOf("DIVISION_BY_ZERO"), listOf("DECIMAL_DIVISION_SCALE_OVERFLOW")), reports.map { it.errors.map { e -> e.rule.name } })
        for (result in reports.drop(1)) {
            val error = result.errors.single().diagnostic
            assertTrue(error.start >= result.start && error.end <= result.end)
            assertTrue(result.parseDiagnostics.isEmpty())
        }
        val sql = "SELECT CAST(7 AS Decimal(12,3))/CAST(0 AS Int8)"
        val diagnostic = report(sql).errors.single().diagnostic
        assertEquals(listOf(diagnostic), combineStatementDiagnostics(sql, sql.indices, listOf(diagnostic), NativeOutcome.Accepted).diagnostics)
        val ast = parseOne(sql, "clickhouse")
        val dump = Serde.dump(ast)
        assertEquals(1, validateClickhouseCompositions(ast, "26.10.2.103").errors.size)
        assertEquals(dump, Serde.dump(ast))
        annotateClickhouseSemanticTypes(ast, "26.10.2.103")
        assertEquals("Decimal(12, 3)", ast.find<Div>()?.type?.sql("clickhouse"))
        assertTrue(clickhouseSemanticDiagnostics(sql, sql.indices, "26.8.34.13").errors.isEmpty())
        val stop = IllegalStateException("cancel")
        assertSame(stop, runCatching { clickhouseScriptSemanticDiagnostics(script, "26.10.2.103") { throw stop } }.exceptionOrNull())
    }

    @Test fun nativeValueAndErrorWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "decimal-diagnostics-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val refinements = Json.parseToJsonElement(File(folder, "decimal-runtime-refinements.json").readText()).jsonObject
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val result = report(sql)
            assertTrue(result.parseDiagnostics.isEmpty(), sql)
            val rules = result.errors.map { it.rule.name }.distinct().sorted()
            assertEquals((refinements[case.getValue("id").jsonPrimitive.content] ?: case.getValue("rules")).jsonArray.map { it.jsonPrimitive.content }, rules, case.getValue("id").toString())
            val generated = parseOne(sql, "clickhouse").sql("clickhouse")
            assertEquals(rules, report(generated).errors.map { it.rule.name }.distinct().sorted())
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql); put("generated", generated)
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseDecimalDiagnosticsOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }

    @Test fun nativeScriptWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "decimal-diagnostics-script-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val reports = clickhouseScriptSemanticDiagnostics(sql, "26.10.2.103")
            assertTrue(reports.all { it.parseDiagnostics.isEmpty() })
            val rules = reports.flatMap { it.errors }.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules, sql)
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql)
                put("generated", Dialects.CLICKHOUSE.parse(sql).filterNotNull().joinToString("; ", postfix = ";") { it.sql("clickhouse") })
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseDecimalDiagnosticsScriptOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
