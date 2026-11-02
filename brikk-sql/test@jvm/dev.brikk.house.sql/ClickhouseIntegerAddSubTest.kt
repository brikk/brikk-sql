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

class ClickhouseIntegerAddSubTest {
    private fun typed(sql: String, schema: Schema? = null) = annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103", schema)
    private fun type(ast: Expression): String = (ast as Select).selects.filterIsInstance<Expression>().first().let {
        if (it.isType(DType.UNKNOWN)) "UNKNOWN" else it.type?.sql("clickhouse") ?: "MISSING"
    }

    @Test fun literalPromotionAndWideIntegerRulesFollowNativeTraits() {
        for ((sql, expected) in mapOf("SELECT 1+2" to "UInt16", "SELECT 1-2" to "Int16",
            "SELECT -0+1" to "UInt16", "SELECT -1+2" to "Int16",
            "SELECT CAST(1 AS UInt32)+CAST(2 AS UInt8)" to "UInt64",
            "SELECT CAST(1 AS UInt64)+CAST(2 AS UInt64)" to "UInt64",
            "SELECT CAST(1 AS UInt128)+CAST(2 AS UInt64)" to "UInt128",
            "SELECT CAST(1 AS UInt128)-CAST(2 AS UInt8)" to "Int128",
            "SELECT CAST(1 AS UInt256)+CAST(2 AS Int8)" to "Int256",
            "SELECT CAST(1 AS UInt256)-CAST(2 AS UInt256)" to "Int256",
            "SELECT (CAST(1 AS UInt8)+CAST(2 AS UInt8))+CAST(3 AS UInt8)" to "UInt32"))
            assertEquals(expected, type(typed(sql)), sql)
    }

    @Test fun schemaDerivedLambdaAndScriptContextsReuseExactTypes() {
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Nullable(Int128)", "b" to "UInt256")), dialect = Dialects.CLICKHOUSE)
        assertEquals("Nullable(Int256)", type(typed("SELECT t.a+t.b FROM t", schema)))
        assertEquals("UInt16", type(typed("SELECT q.v FROM (SELECT 1+2 AS v) q")))
        assertEquals("Array(Int32)", type(typed("SELECT arrayMap((x,y) -> x-y,CAST([7] AS Array(UInt16)),CAST([2] AS Array(Int8)))")))
        assertEquals("Array(UInt16)", type(typed("SELECT arrayMap(x -> plus(x,1),CAST([7] AS Array(UInt8)))")))
        val script = "SET sql_compatible_division_for_integers=1; SELECT (1+2)/2; SELECT 1-2; SELECT 1+2"
        val result = annotateClickhouseScriptSemanticTypes(script, "26.10.2.103")
        assertEquals(listOf("UInt16", "Int16", "UInt16"), result.mapNotNull { (it.expression as? Select)?.let(::type) })
    }

    @Test fun unsupportedOverloadsNullConstantsAndShadowedFunctionsRemainUnproved() {
        for (expression in listOf("CAST(NULL AS Nullable(Int8))+CAST(2 AS Int8)",
            "CAST(1 AS Float64)+CAST(2 AS UInt8)", "CAST(1 AS Decimal(12,3))-CAST(2 AS Decimal(12,3))",
            "'x'+1", "TRUE+1", "TRY_CAST('bad' AS Int8)+CAST(2 AS Int8)", "missing+1")) {
            assertEquals("UNKNOWN", type(typed("SELECT $expression")), expression)
        }
        val shadowed = "WITH ((a,b) -> 'x') AS plus SELECT plus(CAST(1 AS UInt8),CAST(2 AS UInt8))"
        assertEquals("UNKNOWN", type(typed(shadowed)))
        assertTrue(clickhouseSemanticDiagnostics(shadowed, shadowed.indices, "26.10.2.103").errors.isEmpty())
        assertEquals("UNKNOWN", type(typed("SELECT ns.plus(1,2)")))
    }

    @Test fun genericTypingCallerOwnershipOverwriteAndCacheInvalidationStayUnchanged() {
        val sql = "SELECT CAST(1 AS UInt8)+CAST(2 AS UInt8)"
        val generic = annotateTypes(parseOne(sql, "clickhouse"), dialect = Dialects.CLICKHOUSE)
        assertEquals(DType.UTINYINT, generic.find<Add>()?.type?.thisArg)
        assertEquals("Nullable(Int32)", type(annotateTypes(parseOne("SELECT 1+2", "clickhouse"), dialect = Dialects.CLICKHOUSE)))
        fun annotator(metadata: Map<kotlin.reflect.KClass<out Expression>, TypingSpec>? = null, overwrite: kotlin.Boolean = true) =
            TypeAnnotator(MappingSchema(dialect = Dialects.CLICKHOUSE), expressionMetadata = metadata, overwriteTypes = overwrite).also {
                it.clickhouseNativeLiterals = true; it.clickhouseEngineVersion = "26.10.2.103"
            }
        val custom = parseOne(sql, "clickhouse")
        annotator(Dialects.CLICKHOUSE.expressionMetadata + (Add::class to TypingSpec.Returns(DType.DOUBLE))).annotate(custom)
        assertEquals(DType.DOUBLE, custom.find<Add>()?.type?.thisArg)
        val literalPolicy = parseOne("SELECT 1+2", "clickhouse")
        annotator(Dialects.CLICKHOUSE.expressionMetadata + (Literal::class to TypingSpec.Returns(DType.DOUBLE))).annotate(literalPolicy)
        assertEquals(DType.DOUBLE, literalPolicy.find<Literal>()?.type?.thisArg)
        assertEquals("UNKNOWN", type(literalPolicy))
        val negativePolicy = parseOne("SELECT -1+2", "clickhouse")
        annotator(Dialects.CLICKHOUSE.expressionMetadata + (Literal::class to TypingSpec.Returns(DType.DOUBLE))).annotate(negativePolicy)
        assertEquals("UNKNOWN", type(negativePolicy))
        val kept = parseOne(sql, "clickhouse")
        kept.find<Add>()!!.typeSlot = DataType(args("this" to DType.DOUBLE, "nullable" to false))
        annotator(overwrite = false).annotate(kept); assertEquals("Float64", type(kept))
        val cached = parseOne("SELECT 1+2", "clickhouse")
        val typer = annotator()
        typer.annotate(cached); assertEquals("UInt16", type(cached))
        cached.find<Literal>()!!.set("this", "256")
        typer.uncache(cached); typer.annotate(cached); assertEquals("UInt32", type(cached))
        typer.clear(); typer.annotate(cached); assertEquals("UInt32", type(cached))
    }

    @Test fun readOnlyDiagnosticsSourceSerdeCancellationAndVersionBoundariesRemainSeparate() {
        val sql = "-- 😀\nSELECT CAST(18446744073709551615 AS UInt64)+CAST(1 AS UInt8)"
        val ast = parseOne(sql, "clickhouse")
        val dump = Serde.dump(ast)
        assertTrue(validateClickhouseCompositions(ast, "26.10.2.103").errors.isEmpty())
        assertEquals(dump, Serde.dump(ast))
        val generated = ast.sql("clickhouse")
        annotateClickhouseSemanticTypes(ast, "26.10.2.103")
        assertEquals("UInt64", type(ast)); assertEquals(generated, ast.sql("clickhouse"))
        assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression))
        assertFailsWith<IllegalArgumentException> { annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.8.34.13") }
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103") { throw stop } }.exceptionOrNull())
    }

    @Test fun all2304IndependentNativePairTypesMatchTheNativeOnlyAnnotator() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic/integer-add-sub-types")
        val input = Json.parseToJsonElement(File(folder, "input.json").readText()).jsonObject.getValue("batches").jsonArray
        val expected = Json.parseToJsonElement(File(folder, "26.10.2.103.json").readText()).jsonObject.getValue("batches").jsonArray
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("types") }
        val rows = input.map { element ->
            val batch = element.jsonObject
            val id = batch.getValue("id").jsonPrimitive.content
            val ast = typed(batch.getValue("sql").jsonPrimitive.content) as Select
            val actual = buildJsonObject {
                for (projection in ast.selects.filterIsInstance<Alias>()) {
                    val operation = projection.walk().first { clickhouseIntegerAddSubCall(it) != null }
                    put(projection.alias, operation.type?.sql("clickhouse") ?: "MISSING")
                }
            }
            assertEquals(144, actual.size)
            assertEquals(expected.getValue(id), actual, id)
            buildJsonObject { put("id", id); put("types", actual) }
        }
        assertEquals(16, rows.size)
        System.getProperty("brikk.clickhouseIntegerAddSubTypesOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }

    @Test fun nativeFiniteWitnesses() = capture("integer-add-sub", "brikk.clickhouseIntegerAddSubOut", false)
    @Test fun nativeScriptWitnesses() = capture("integer-add-sub-script", "brikk.clickhouseIntegerAddSubScriptOut", true)

    private fun capture(name: String, property: String, script: kotlin.Boolean) {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "$name-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val units = annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103")
            assertTrue(units.all { it.expression != null && it.diagnostics.parseDiagnostics.isEmpty() }, sql)
            val actualTypes = units.mapNotNull { (it.expression as? Select)?.let(::type) }
            val expectedTypes = if (script) case.getValue("types").jsonArray.map { it.jsonPrimitive.content } else listOf(case.getValue("type").jsonPrimitive.content)
            assertEquals(expectedTypes, actualTypes, case.getValue("id").toString())
            val rules = units.flatMap { it.diagnostics.errors }.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules)
            for (unit in units) assertEquals(Serde.dump(unit.expression!!), Serde.dump(Serde.load(Serde.dump(unit.expression!!)) as Expression))
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql)
                put("generated", units.joinToString("; ", postfix = ";") { it.expression!!.sql("clickhouse") })
                if (script) put("types", JsonArray(actualTypes.map(::JsonPrimitive))) else put("result_type", actualTypes.single())
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty(property)?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
