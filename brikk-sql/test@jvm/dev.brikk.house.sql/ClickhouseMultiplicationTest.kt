package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseMultiplicationTest {
    private val version = "26.10.2.103"
    private fun typed(sql: String) = annotateClickhouseSemanticTypesWithProvenance(parseOne(sql, "clickhouse"), version)
    private fun type(ast: Expression): String = (ast as Select).selects.filterIsInstance<Expression>().first().let {
        if (it.isType(DType.UNKNOWN)) "UNKNOWN" else it.type?.sql("clickhouse") ?: "MISSING"
    }

    @Test fun standaloneAndNestedResultsHaveNativeProofRatherThanGenericFallback() {
        for ((sql, expected) in mapOf("SELECT 1*2" to "UInt16", "SELECT 1e0*(2e0+3.0)" to "Float64",
            "SELECT (1*2)*3" to "UInt32", "SELECT 1*(2+3)" to "UInt32", "SELECT multiply(1,2)" to "UInt16",
            "SELECT CAST(1 AS UInt256)*CAST(2 AS Int8)" to "Int256")) {
            val result = typed(sql)
            assertEquals(expected, type(result.expression), sql)
            for (operation in result.expression.walk().filter { clickhouseMultiplicationCall(it) != null })
                assertEquals(ClickhouseTypeEvidence.NATIVE_CONDITIONAL, result.provenance.factFor(operation)!!.evidence, sql)
        }
        val generic = annotateTypes(parseOne("SELECT 1*2", "clickhouse"), dialect = Dialects.CLICKHOUSE)
        assertEquals("Nullable(Int32)", type(generic))
    }

    @Test fun schemaAndExplicitScriptSettingsPropagateWithoutInventingState() {
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Nullable(Int128)", "b" to "UInt256")), dialect = Dialects.CLICKHOUSE)
        val result = annotateClickhouseSemanticTypesWithProvenance(parseOne("SELECT t.a*t.b FROM t", "clickhouse"), version, schema)
        assertEquals("Nullable(Int256)", type(result.expression))
        assertEquals(ClickhouseTypeEvidence.NATIVE_CONDITIONAL, result.provenance.factFor(result.expression.find<Mul>()!!)!!.evidence)
        val script = "SET sql_compatible_division_for_integers=1; SELECT (1*2)/2"
        val units = annotateClickhouseScriptSemanticTypesWithProvenance(script, version)
        assertEquals("UInt16", type(units.last().annotation.expression!!))
        assertEquals("UNKNOWN", type(typed("SELECT (1*2)/2").expression))
        assertEquals("UInt16", type(typed("SELECT q.v FROM (SELECT 1*2 AS v) q").expression))
    }

    @Test fun unsupportedDispatchAndCallerPoliciesNeverBecomeNativeProofs() {
        for (expression in listOf("CAST(1 AS Float64)*CAST(2 AS Int128)",
            "CAST(NULL AS Nullable(Int8))*CAST(2 AS UInt8)", "CAST(1 AS Decimal(12,3))*CAST(2 AS Decimal(12,3))",
            "TRUE*2", "'x'*2", "TRY_CAST('bad' AS Int8)*2", "missing*2")) {
            val result = typed("SELECT $expression")
            assertEquals("UNKNOWN", type(result.expression), expression)
        }
        val shadowed = typed("WITH ((a,b) -> 'x') AS multiply SELECT multiply(1,2)")
        assertEquals("UNKNOWN", type(shadowed.expression))
        val qualified = typed("SELECT ns.multiply(1,2)")
        assertTrue(qualified.expression.walk().filterIsInstance<Mul>().all {
            qualified.provenance.factFor(it)!!.evidence != ClickhouseTypeEvidence.NATIVE_CONDITIONAL
        })
        val custom = parseOne("SELECT 1*2", "clickhouse")
        TypeAnnotator(MappingSchema(dialect = Dialects.CLICKHOUSE),
            expressionMetadata = Dialects.CLICKHOUSE.expressionMetadata + (Mul::class to TypingSpec.Returns(DType.DOUBLE))).also {
            it.clickhouseNativeLiterals = true; it.clickhouseEngineVersion = version
        }.annotate(custom)
        assertEquals(DType.DOUBLE, custom.find<Mul>()!!.type!!.thisArg)
    }

    @Test fun all1440IndependentPrimitiveTypesMatchTheNativeProofPass() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic/multiplication-types")
        val input = Json.parseToJsonElement(File(folder, "input.json").readText()).jsonObject.getValue("batches").jsonArray
        val native = Json.parseToJsonElement(File(folder, "$version.json").readText()).jsonObject.getValue("batches").jsonArray
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("types") }
        val rows = input.map { element ->
            val batch = element.jsonObject
            val id = batch.getValue("id").jsonPrimitive.content
            val result = typed(batch.getValue("sql").jsonPrimitive.content)
            val actual = buildJsonObject {
                for (projection in (result.expression as Select).selects.filterIsInstance<Alias>()) {
                    val operation = projection.walk().first { clickhouseMultiplicationCall(it) != null }
                    val fact = result.provenance.factFor(operation)!!
                    assertEquals(ClickhouseTypeEvidence.NATIVE_CONDITIONAL, fact.evidence)
                    put(projection.alias, fact.typeSql ?: "MISSING")
                }
            }
            assertEquals(180, actual.size)
            assertEquals(native.getValue(id), actual, id)
            buildJsonObject { put("id", id); put("types", actual) }
        }
        System.getProperty("brikk.clickhouseMultiplicationTypesOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }

    @Test fun nativeFiniteAndScriptWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        for ((name, property) in listOf("multiplication" to "brikk.clickhouseMultiplicationOut",
            "multiplication-script" to "brikk.clickhouseMultiplicationScriptOut")) {
            val cases = Json.parseToJsonElement(File(folder, "$name-cases.json").readText()).jsonObject.getValue("execution").jsonArray
            val rows = cases.map { element ->
                val case = element.jsonObject
                val sql = case.getValue("sql").jsonPrimitive.content
                val units = annotateClickhouseScriptSemanticTypesWithProvenance(sql, version)
                assertTrue(units.all { it.annotation.expression != null && it.annotation.diagnostics.parseDiagnostics.isEmpty() }, sql)
                val types = units.mapNotNull { (it.annotation.expression as? Select)?.let(::type) }
                val expected = case["types"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: listOf(case.getValue("type").jsonPrimitive.content)
                assertEquals(expected, types, sql)
                val rules = units.flatMap { it.annotation.diagnostics.errors }.map { it.rule.name }.distinct().sorted()
                assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules, sql)
                val generated = units.joinToString("; ", postfix = ";") { it.annotation.expression!!.sql("clickhouse") }
                val again = annotateClickhouseScriptSemanticTypes(generated, version)
                assertEquals(types, again.mapNotNull { (it.expression as? Select)?.let(::type) })
                for (unit in units) assertEquals(Serde.dump(unit.annotation.expression!!),
                    Serde.dump(Serde.load(Serde.dump(unit.annotation.expression!!)) as Expression))
                buildJsonObject {
                    put("id", case.getValue("id")); put("input_sql", sql); put("generated", generated)
                    put("types", JsonArray(types.map(::JsonPrimitive))); put("rules", JsonArray(rules.map(::JsonPrimitive)))
                }
            }
            System.getProperty(property)?.let { File(it).writeText(
                Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
        }
    }
}
