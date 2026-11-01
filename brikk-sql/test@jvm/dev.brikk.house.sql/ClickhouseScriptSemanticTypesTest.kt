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

class ClickhouseScriptSemanticTypesTest {
    private fun typed(script: String, schema: Schema? = null) = annotateClickhouseScriptSemanticTypes(script, "26.10.2.103", schema)
    private fun type(expression: Expression): String = (expression as Select).selects.filterIsInstance<Expression>().first().let {
        if (it.isType(DType.UNKNOWN)) "UNKNOWN" else it.type?.sql("clickhouse") ?: "MISSING"
    }
    private fun types(script: String) = typed(script).mapNotNull { (it.expression as? Select)?.let(::type) }

    @Test fun callersExplicitlyChooseScriptRatherThanStatementMode() {
        assertEquals(listOf("UInt8"), types("SET sql_compatible_division_for_integers=1; SELECT 7/2"))
        assertEquals(listOf("Float64"), types("SET sql_compatible_division_for_integers=0; SELECT 7/2"))
        assertEquals(listOf("UNKNOWN"), types("SELECT 7/2;"))
        val statement = annotateClickhouseSemanticTypes(parseOne("SELECT 7/2", "clickhouse"), "26.10.2.103")
        assertEquals("UNKNOWN", type(statement))
        val returned = typed("SET sql_compatible_division_for_integers=1; SELECT 7/2").last().expression!!
        assertEquals("UNKNOWN", type(annotateClickhouseSemanticTypes(returned, "26.10.2.103")))
        val source = "SET sql_compatible_division_for_integers=1; SELECT 1/0"
        assertTrue(clickhouseSemanticDiagnostics(source, source.indexOf("SELECT") until source.length, "26.10.2.103").errors.isEmpty())
        assertTrue(typed("-- ; SET sql_compatible_division_for_integers=1\nSELECT 7/2").single().diagnostics.errors.isEmpty())
        assertEquals(listOf("UNKNOWN"), types("-- SET sql_compatible_division_for_integers=1;\nSELECT 7/2"))
    }

    @Test fun localNestedAndLambdaSettingsShareTheExistingScopeRules() {
        val script = "SET sql_compatible_division_for_integers=1; SELECT 7/2 SETTINGS sql_compatible_division_for_integers=0; SELECT 7/2; SELECT q.v FROM (SELECT 7/2 AS v SETTINGS sql_compatible_division_for_integers=0) q; SELECT arrayMap(x -> x/2,CAST([7] AS Array(Int16)))"
        assertEquals(listOf("Float64", "UInt8", "Float64", "Array(Int16)"), types(script))
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Nullable(Int128)", "b" to "UInt256")), dialect = Dialects.CLICKHOUSE)
        val result = typed("SET sql_compatible_division_for_integers=1; SELECT t.a/t.b FROM t", schema)
        assertEquals("Nullable(Int128)", type(result.last().expression!!))
        assertEquals(listOf("UNKNOWN", "UInt8"), types("SET sql_compatible_division_for_integers=1; SELECT 7/2 SETTINGS sql_compatible_division_for_integers={mode:UInt8}; SELECT 7/2"))
    }

    @Test fun resetsUnknownAssignmentsUnsupportedMutationsAndFreshCallsDoNotInventState() {
        for (assignment in listOf("DEFAULT", "{mode:UInt8}", "2", "some_value")) {
            val script = "SET sql_compatible_division_for_integers=1; SET sql_compatible_division_for_integers=$assignment; SELECT 7/2"
            val result = typed(script)
            assertEquals("UNKNOWN", type(result.last().expression!!), assignment)
            assertTrue(result[1].diagnostics.unknowns.any { it.reason == ClickhouseSemanticUnknownReason.SETTING_STATE })
        }
        assertEquals(listOf("UNKNOWN"), types("SET sql_compatible_division_for_integers=1; SET compatibility='26.8'; SELECT 7/2"))
        assertEquals(listOf("UNKNOWN"), types("SET sql_compatible_division_for_integers=1; RESET ALL; SELECT 7/2"))
        assertEquals(listOf("UNKNOWN"), types("SET sql_compatible_division_for_integers=DEFAULT,sql_compatible_division_for_integers=1; SELECT 7/2"))
        assertEquals(listOf("Float64"), types("SET sql_compatible_division_for_integers=1,sql_compatible_division_for_integers=0; SELECT 7/2"))
        assertEquals(listOf("UNKNOWN"), types("SELECT 7/2"))
    }

    @Test fun sharedFramingPositionsParseFailuresAndInlineDataStayDistinct() {
        val script = "-- 😀\nSET sql_compatible_division_for_integers=1;\nSELECT 7/2; SELECT ("
        val result = typed(script)
        assertEquals(3, result.size)
        assertEquals("UInt8", type(result[1].expression!!))
        assertNull(result[2].expression)
        assertTrue(result[2].diagnostics.parseDiagnostics.isNotEmpty() || result[2].diagnostics.unknowns.any { it.reason == ClickhouseSemanticUnknownReason.STATEMENT_BOUNDARY })
        assertEquals(clickhouseScriptSemanticDiagnostics(script, "26.10.2.103"), result.map { it.diagnostics })
        val division = result[1].expression!!.find<Div>()!!
        val origin = assertNotNull(division.syntaxOrigin).range
        val start = result[1].diagnostics.start
        assertTrue(origin.start + start >= script.indexOf("SELECT") && origin.end + start <= result[1].diagnostics.end)
        val inline = "INSERT INTO t FORMAT CSV\nSET sql_compatible_division_for_integers=1;\n;\nSELECT 7/2"
        assertEquals(listOf("UNKNOWN"), types(inline))
        assertTrue(typed("-- trivia only ;\n").isEmpty())
        assertTrue(typed("").isEmpty())
    }

    @Test fun astsAreIndependentlyOwnedAndSettingsAreNeverInjectedOrRewritten() {
        val script = "SET sql_compatible_division_for_integers=1; SELECT 7/2; SELECT 7/2"
        val result = typed(script)
        val originals = Dialects.CLICKHOUSE.parse(script).filterNotNull()
        assertEquals(originals.map { it.sql("clickhouse") }, result.map { it.expression!!.sql("clickhouse") })
        assertNull(result[1].expression!!.args["settings"])
        val first = result[1].expression!!.find<Div>()!!
        val second = result[2].expression!!.find<Div>()!!
        assertNotSame(first, second)
        first.typeSlot = DataType(args("this" to DType.DOUBLE, "nullable" to false))
        assertEquals("UInt8", type(result[2].expression!!))
        assertEquals(listOf("UInt8", "UInt8"), types(script))
        for (unit in result) assertEquals(Serde.dump(unit.expression!!), Serde.dump(Serde.load(Serde.dump(unit.expression!!)) as Expression))
        val once = typed("SELECT 'SET sql_compatible_division_for_integers=1;' AS text,7/2")
        assertTrue(once.single().diagnostics.errors.isEmpty())
        assertEquals(DType.UNKNOWN, once.single().expression!!.find<Div>()?.type?.thisArg)
    }

    @Test fun conditionalTypesAndDiagnosticsAgreeWithoutClaimingExecution() {
        val script = "SET sql_compatible_division_for_integers=1; SELECT 1/0"
        val result = typed(script)
        assertEquals("UInt8", type(result.last().expression!!))
        assertEquals(ClickhouseSemanticRule.DIVISION_BY_ZERO, result.last().diagnostics.errors.single().rule)
        assertEquals(clickhouseScriptSemanticDiagnostics(script, "26.10.2.103"), result.map { it.diagnostics })
        val decimal = "SET decimal_check_overflow=0; SELECT CAST('0.000001' AS Decimal32(6))/CAST('0.0001' AS Decimal32(4))"
        assertEquals(listOf("Decimal(9, 6)"), types(decimal))
        val checked = typed(decimal.replace("overflow=0", "overflow=1"))
        assertEquals("UNKNOWN", type(checked.last().expression!!))
        assertEquals(ClickhouseSemanticRule.DECIMAL_DIVISION_SCALE_OVERFLOW, checked.last().diagnostics.errors.single().rule)
    }

    @Test fun exactPinSchemaAndCancellationContractsRemainExplicit() {
        assertFailsWith<IllegalArgumentException> { annotateClickhouseScriptSemanticTypes("", "26.8.34.13") }
        assertFailsWith<IllegalArgumentException> { annotateClickhouseScriptSemanticTypes("SELECT 1", "26.10.2.103", MappingSchema(dialect = Dialects.DUCKDB)) }
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { annotateClickhouseScriptSemanticTypes("SET sql_compatible_division_for_integers=1; SELECT 7/2", "26.10.2.103") { throw stop } }.exceptionOrNull())
    }

    @Test fun nativeWholeSessionWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "script-typing-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val result = typed(sql)
            assertTrue(result.all { it.expression != null && it.diagnostics.parseDiagnostics.isEmpty() }, sql)
            assertEquals(clickhouseScriptSemanticDiagnostics(sql, "26.10.2.103"), result.map { it.diagnostics })
            val actualTypes = result.mapNotNull { (it.expression as? Select)?.let(::type) }
            assertEquals(case.getValue("types").jsonArray.map { it.jsonPrimitive.content }, actualTypes, case.getValue("id").toString())
            val rules = result.flatMap { it.diagnostics.errors }.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules, sql)
            val generated = result.joinToString("; ", postfix = ";") { it.expression!!.sql("clickhouse") }
            assertEquals(actualTypes, types(generated))
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql); put("generated", generated)
                put("types", JsonArray(actualTypes.map(::JsonPrimitive))); put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseScriptTypesOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
