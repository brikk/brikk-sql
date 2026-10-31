package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.dialects.sql
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseDivisionDiagnosticsTest {
    @Test fun nativeWitnesses() {
        val data = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val rows = Json.parseToJsonElement(File(data, "division-diagnostics-cases.json").readText()).jsonObject.getValue("execution").jsonArray.map {
            val case = it.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
            assertTrue(report.parseDiagnostics.isEmpty(), sql)
            val rules = report.errors.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules, sql)
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql)
                put("generated", parseOne(sql, "clickhouse").sql("clickhouse"))
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseDivisionDiagnosticsOut")?.let { path -> File(path).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
    private fun report(expression: String, setting: String = "1") =
        clickhouseSemanticDiagnostics("SELECT $expression SETTINGS sql_compatible_division_for_integers=$setting",
            ("SELECT $expression SETTINGS sql_compatible_division_for_integers=$setting").indices, "26.10.2.103")

    @Test fun constantsProveZeroAndFirstOperandBoundsWithoutErasingTypes() {
        for (bits in listOf(8,16,32,64,128,256)) {
            val zero = report("CAST(1 AS UInt$bits)/CAST(0 AS UInt$bits)")
            assertEquals(listOf(ClickhouseSemanticRule.DIVISION_BY_ZERO), zero.errors.map { it.rule })
            assertEquals(153, zero.errors.single().nativeErrorCode)
        }
        for (expression in listOf("CAST(-128 AS Int8)/CAST(-1 AS Int8)", "CAST(255 AS UInt8)/CAST(1 AS Int256)",
            "divideExact(CAST(255 AS UInt8),CAST(-1 AS Int16))")) {
            assertEquals(listOf(ClickhouseSemanticRule.INTEGER_DIVISION_OVERFLOW), report(expression).errors.map { it.rule }, expression)
            val ast = annotateClickhouseSemanticTypes(parseOne("SELECT $expression SETTINGS sql_compatible_division_for_integers=1", "clickhouse"), "26.10.2.103")
            assertFalse((ast as Select).selects.filterIsInstance<Expression>().first().isType(DType.UNKNOWN))
        }
    }

    @Test fun maskingUnknownValuesWrappingCastsAndFloatingDelegatesDoNotProveErrors() {
        for (expression in listOf("CAST(NULL AS Nullable(Int8))/CAST(0 AS Int8)",
            "CAST(1 AS Int8)/CAST(NULL AS Nullable(Int8))", "x/CAST(0 AS UInt8)",
            "CAST(256 AS UInt8)/CAST(0 AS UInt8)", "CAST(1 AS Float32)/CAST(0 AS UInt8)",
            "CAST('255' AS UInt8)/CAST(1 AS Int8)",
            "CAST(18446744073709551616 AS UInt256)/CAST(0 AS UInt8)",
            "CAST(255 AS UInt8)/CAST(2 AS Int8)", "CAST(-128 AS Int8)/CAST(1 AS Int8)")) {
            assertTrue(report(expression).errors.isEmpty(), expression)
        }
        assertTrue(report("1/0", "0").errors.isEmpty())
        val sql = "SELECT CAST(1 AS Int8)/CAST(0 AS Int8)"
        assertTrue(clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103").errors.isEmpty())
        assertTrue(clickhouseSemanticDiagnostics(sql, sql.indices, "26.8.34.13").errors.isEmpty())
    }

    @Test fun nullableConstantsPositionsParserSeparationAndCancellation() {
        assertEquals(ClickhouseSemanticRule.DIVISION_BY_ZERO,
            report("CAST(1 AS Nullable(Int8))/CAST(0 AS Nullable(Int8))").errors.single().rule)
        val script = "-- 😀\nSELECT 1;\nSELECT divideExact(CAST(1 AS Int8),CAST(0 AS Int8));"
        val report = clickhouseScriptSemanticDiagnostics(script, "26.10.2.103").last()
        val diagnostic = report.errors.single().diagnostic
        assertTrue(diagnostic.start >= script.indexOf("divideExact"))
        assertTrue(diagnostic.end <= script.length)
        assertTrue(report.parseDiagnostics.isEmpty())
        val stop = IllegalStateException("cancel")
        assertSame(stop, runCatching { clickhouseScriptSemanticDiagnostics(script, "26.10.2.103") { throw stop } }.exceptionOrNull())
    }
}
