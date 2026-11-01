package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.optimizer.Schema
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseDecimalDivisionTest {
    private fun typed(sql: String, schema: Schema? = null, native: kotlin.Boolean = true): Expression =
        if (native) annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103", schema)
        else annotateTypes(parseOne(sql, "clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE)
    private fun type(ast: Expression): String = (ast as Select).selects.filterIsInstance<Expression>().first().let {
        if (it.isType(DType.UNKNOWN)) "UNKNOWN" else it.type?.sql("clickhouse") ?: "MISSING"
    }

    @Test fun independentNativeTypesMatchAllAdmittedPairs() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic/decimal-division-types")
        val input = Json.parseToJsonElement(File(folder, "input.json").readText()).jsonObject
        val expected = Json.parseToJsonElement(File(folder, "26.10.2.103.json").readText()).jsonObject
            .getValue("batches").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("types") }
        val rows = input.getValue("batches").jsonArray.map {
            val batch = it.jsonObject
            val id = batch.getValue("id").jsonPrimitive.content
            val ast = typed(batch.getValue("sql").jsonPrimitive.content) as Select
            val actual = buildJsonObject {
                for (projection in ast.selects.filterIsInstance<Alias>()) {
                    val operation = projection.walk().first { node -> clickhouseDivisionCall(node) != null }
                    put(projection.alias, operation.type?.sql("clickhouse") ?: "MISSING")
                }
            }
            assertEquals(228, actual.size)
            assertEquals(expected.getValue(id), actual, id)
            buildJsonObject { put("id", id); put("types", actual) }
        }
        System.getProperty("brikk.clickhouseDecimalDivisionTypesOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }

    @Test fun decimalPrecisionIsNotAlwaysTheStoragePrecision() {
        for (native in listOf(true, false)) {
            assertEquals("Decimal(5, 2)", type(typed("SELECT CAST(7 AS Decimal(5,2))/CAST(2 AS Int256)", native = native)))
            assertEquals("Decimal(5, 2)", type(typed("SELECT CAST(7 AS UInt256)/CAST(2 AS Decimal(5,2))", native = native)))
            assertEquals("Decimal(18, 3)", type(typed("SELECT CAST(7 AS Decimal(12,3))/CAST(2 AS Decimal(5,2))", native = native)))
            assertEquals("Decimal(18, 3)", type(typed("SELECT CAST(7 AS Decimal(12,3))/CAST(2 AS Decimal(12,3))", native = native)))
        }
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Nullable(Decimal(12,3))", "b" to "UInt256")), dialect = Dialects.CLICKHOUSE)
        assertEquals("Nullable(Decimal(12, 3))", type(typed("SELECT t.a/t.b FROM t", schema)))
        assertEquals("Array(Decimal(12, 3))", type(typed("SELECT arrayMap((a,b) -> a/b,CAST([7] AS Array(Decimal(12,3))),CAST([2] AS Array(Int8)))")))
    }

    @Test fun uncertainScaleDispatchNullConstantsAndCastsRemainUnknown() {
        for (expression in listOf("CAST(7 AS Decimal(5,2))/CAST(2 AS Decimal(12,3))",
            "CAST('0.000001' AS Decimal32(6))/CAST('0.0001' AS Decimal32(4))",
            "CAST(NULL AS Nullable(Decimal(12,3)))/CAST(2 AS Int8)",
            "TRY_CAST('bad' AS Decimal(12,3))/CAST(2 AS Int8)")) {
            assertEquals("UNKNOWN", type(typed("SELECT $expression")), expression)
        }
        val sql = "SELECT CAST('0.000001' AS Decimal32(6))/CAST('0.0001' AS Decimal32(4))"
        assertEquals("Decimal(9, 6)", type(typed("$sql SETTINGS decimal_check_overflow=0")))
        val report = clickhouseScriptSemanticDiagnostics("SET decimal_check_overflow=0; $sql", "26.10.2.103").last()
        assertFalse(report.unknowns.any { it.reason == ClickhouseSemanticUnknownReason.RESULT_FAMILY })
        assertTrue(report.errors.isEmpty())
    }

    @Test fun nativeValueErrorWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "decimal-division-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val refinements = Json.parseToJsonElement(File(folder, "decimal-diagnostics-refinements.json").readText()).jsonObject
        val rows = cases.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val sql = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(sql, "clickhouse")
            val generated = ast.sql("clickhouse")
            assertEquals(ast, parseOne(generated, "clickhouse"), id)
            annotateClickhouseSemanticTypes(ast, "26.10.2.103")
            val type = type(ast)
            assertEquals(case.getValue("type").jsonPrimitive.content, type, id)
            assertEquals(generated, ast.sql("clickhouse"))
            assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression))
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
            assertTrue(report.parseDiagnostics.isEmpty(), id)
            val rules = report.errors.map { it.rule.name }.distinct().sorted()
            assertEquals((refinements[id] ?: case.getValue("rules")).jsonArray.map { it.jsonPrimitive.content }, rules, id)
            buildJsonObject {
                put("id", id); put("input_sql", sql); put("generated", generated); put("result_type", type)
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseDecimalDivisionOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
