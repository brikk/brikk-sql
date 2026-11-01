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

class ClickhouseDecimalRuntimeOverflowTest {
    private val narrowing = "CAST(2147483648 AS UInt64)/CAST(1 AS Decimal32(0))"
    private val multiplier = "CAST(0 AS UInt8)/CAST(1 AS Decimal64(10))"
    private val numerator = "CAST('21474836.5' AS Decimal32(1))/CAST('0.1' AS Decimal32(1))"
    private fun report(sql: String) = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
    private fun rules(expression: String, setting: String = "1") = report(
        "SELECT $expression SETTINGS decimal_check_overflow=$setting").errors.map { it.rule.name }

    @Test fun narrowingMultiplierAndNumeratorUseDistinctRuntimeRules() {
        assertEquals(listOf("DECIMAL_DIVISION_NARROWING_OVERFLOW"), rules(narrowing))
        assertEquals(listOf("DECIMAL_DIVISION_NARROWING_OVERFLOW"), rules("CAST(1 AS Decimal32(0))/CAST(2147483648 AS UInt64)"))
        assertEquals(listOf("DECIMAL_DIVISION_MULTIPLIER_OVERFLOW"), rules(multiplier))
        assertEquals(listOf("DECIMAL_DIVISION_NUMERATOR_OVERFLOW"), rules(numerator))
        assertEquals(listOf("DECIMAL_DIVISION_NUMERATOR_OVERFLOW"), rules("CAST(21474837 AS Int64)/CAST(1 AS Decimal32(1))"))
        assertEquals(listOf("DECIMAL_DIVISION_NARROWING_OVERFLOW"), rules("CAST(2147483648 AS UInt64)/CAST(0 AS Decimal32(5))"))
        assertEquals(listOf("DECIMAL_DIVISION_MULTIPLIER_OVERFLOW"), rules("CAST(0 AS UInt8)/CAST(0 AS Decimal64(10))"))
        assertEquals(listOf("DECIMAL_DIVISION_NUMERATOR_OVERFLOW"), rules("CAST(7 AS Decimal32(6))/CAST(0 AS Decimal32(3))"))
    }

    @Test fun boundariesMaskingUnknownSettingsAndUncheckedBigDecimalsStayUnproved() {
        for (expression in listOf("CAST(-2147483648 AS Int64)/CAST(1 AS Decimal32(0))",
            "CAST('21474836.4' AS Decimal32(1))/CAST('0.1' AS Decimal32(1))",
            "CAST(21474836 AS Int64)/CAST(1 AS Decimal32(1))",
            "CAST(NULL AS Nullable(Decimal32(0)))/CAST(2147483648 AS UInt64)",
            "CAST(2147483648 AS UInt64)/CAST(NULL AS Nullable(Decimal32(0)))",
            "CAST('bad' AS Decimal32(1))/CAST('0.1' AS Decimal32(1))",
            "CAST(256 AS UInt8)/CAST(1 AS Decimal32(5))",
            "CAST('${"9".repeat(37)}.0' AS Decimal128(1))/CAST('0.1' AS Decimal128(1))",
            "CAST('${"9".repeat(75)}.0' AS Decimal256(1))/CAST('0.1' AS Decimal256(1))")) {
            assertTrue(rules(expression).isEmpty(), expression)
        }
        for (expression in listOf(narrowing, multiplier, numerator)) {
            assertTrue(rules(expression, "0").isEmpty(), expression)
            assertTrue(report("SELECT $expression").errors.isEmpty(), expression)
            assertTrue(rules(expression, "{enabled:UInt8}").isEmpty(), expression)
        }
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "UInt64")), dialect = Dialects.CLICKHOUSE)
        val sql = "SELECT t.a/CAST(1 AS Decimal32(0)) FROM t SETTINGS decimal_check_overflow=1"
        assertTrue(clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103", schema).errors.isEmpty())
    }

    @Test fun scriptStateAndQueryLocalOverridesKeepTheirScope() {
        val sql = "SET decimal_check_overflow=1; SELECT $narrowing; SELECT $narrowing SETTINGS decimal_check_overflow=0; SELECT $multiplier"
        val reports = clickhouseScriptSemanticDiagnostics(sql, "26.10.2.103")
        assertEquals(listOf(emptyList(), listOf("DECIMAL_DIVISION_NARROWING_OVERFLOW"), emptyList(), listOf("DECIMAL_DIVISION_MULTIPLIER_OVERFLOW")),
            reports.map { it.errors.map { e -> e.rule.name } })
        assertTrue(clickhouseScriptSemanticDiagnostics("SET decimal_check_overflow=1; SET decimal_check_overflow=DEFAULT; SELECT $narrowing", "26.10.2.103").last().errors.isEmpty())
        assertTrue(report("SELECT $narrowing").errors.isEmpty())
    }

    @Test fun conditionalTypesPositionsParserVerdictsReadOnlyAndCancellation() {
        val sql = "-- 😀\nSELECT $numerator SETTINGS decimal_check_overflow=1"
        val result = report(sql)
        assertTrue(result.parseDiagnostics.isEmpty())
        val error = result.errors.single()
        assertEquals(407, error.nativeErrorCode)
        assertTrue(error.diagnostic.start >= sql.indexOf("CAST") && error.diagnostic.end <= sql.length)
        assertEquals(listOf(error.diagnostic), combineStatementDiagnostics(sql, sql.indices, listOf(error.diagnostic), NativeOutcome.Accepted).diagnostics)
        val ast = parseOne(sql, "clickhouse")
        val dump = Serde.dump(ast)
        assertEquals(1, validateClickhouseCompositions(ast, "26.10.2.103").errors.size)
        assertEquals(dump, Serde.dump(ast))
        val generated = ast.sql("clickhouse")
        annotateClickhouseSemanticTypes(ast, "26.10.2.103")
        assertEquals("Decimal(9, 1)", ast.find<Div>()?.type?.sql("clickhouse"))
        assertEquals(generated, ast.sql("clickhouse"))
        assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression))
        assertTrue(clickhouseSemanticDiagnostics(sql, sql.indices, "26.8.34.13").errors.isEmpty())
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { clickhouseScriptSemanticDiagnostics(sql, "26.10.2.103") { throw stop } }.exceptionOrNull())
    }

    @Test fun nativeFiniteWitnesses() = capture("decimal-runtime", "brikk.clickhouseDecimalRuntimeOut", script = false)
    @Test fun nativeScriptWitnesses() = capture("decimal-runtime-script", "brikk.clickhouseDecimalRuntimeScriptOut", script = true)

    private fun capture(name: String, property: String, script: kotlin.Boolean) {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "$name-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val reports = if (script) clickhouseScriptSemanticDiagnostics(sql, "26.10.2.103") else listOf(report(sql))
            assertTrue(reports.all { it.parseDiagnostics.isEmpty() }, sql)
            val rules = reports.flatMap { it.errors }.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules, case.getValue("id").toString())
            val generated = Dialects.CLICKHOUSE.parse(sql).filterNotNull().joinToString("; ", postfix = ";") { it.sql("clickhouse") }
            assertEquals(rules, clickhouseScriptSemanticDiagnostics(generated, "26.10.2.103").flatMap { it.errors }.map { it.rule.name }.distinct().sorted())
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql); put("generated", generated)
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty(property)?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
