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

class ClickhouseFloatingAddSubTest {
    private fun typed(sql: String, schema: Schema? = null) = annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103", schema)
    private fun type(ast: Expression): String = (ast as Select).selects.filterIsInstance<Expression>().first().let {
        if (it.isType(DType.UNKNOWN)) "UNKNOWN" else it.type?.sql("clickhouse") ?: "MISSING"
    }

    @Test fun supportedFloatingAndMixedIntegerResultsPromoteToFloat64() {
        for (expression in listOf("CAST(1 AS Float32)+CAST(2 AS Float32)", "CAST(1 AS Float64)-CAST(2 AS Float32)",
            "CAST(1 AS UInt64)+CAST(2 AS Float32)", "CAST(1 AS Float32)-CAST(2 AS Int64)",
            "1.5+2", "18446744073709551616+0.5", "-9223372036854775809-0.5", "plus(CAST(1 AS Float32),2)"))
            assertEquals("Float64", type(typed("SELECT $expression")), expression)
        assertEquals("UInt16", type(typed("SELECT 1+2")))
        assertEquals("Int16", type(typed("SELECT 1-2")))
    }

    @Test fun nullableSchemaDerivedLambdaAndScriptContextsShareTheRules() {
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Nullable(Float32)", "b" to "UInt64")), dialect = Dialects.CLICKHOUSE)
        assertEquals("Nullable(Float64)", type(typed("SELECT t.a-t.b FROM t", schema)))
        assertEquals("Float64", type(typed("SELECT q.v FROM (SELECT CAST(1 AS Float32)+2 AS v) q")))
        assertEquals("Array(Float64)", type(typed("SELECT arrayMap((a,b) -> a+b,CAST([7] AS Array(Float32)),CAST([2] AS Array(Int64)))")))
        assertEquals("Array(Nullable(Float64))", type(typed("SELECT arrayMap(x -> x-1,CAST([7] AS Array(Nullable(Float32))))")))
        val script = "SET sql_compatible_division_for_integers=1; SELECT (CAST(7 AS Float32)+2)/2; SELECT CAST(7 AS Float32)+2 SETTINGS sql_compatible_division_for_integers=0"
        val result = annotateClickhouseScriptSemanticTypes(script, "26.10.2.103")
        assertEquals(listOf("Float64", "Float64"), result.mapNotNull { (it.expression as? Select)?.let(::type) })
    }

    @Test fun wideIntegersNullConstantsOtherOverloadsAndUnresolvedCallsRemainUnknown() {
        for (expression in listOf("CAST(1 AS Float32)+CAST(2 AS Int128)", "CAST(1 AS UInt256)-CAST(2 AS Float64)",
            "CAST(NULL AS Nullable(Float32))+CAST(2 AS Float64)", "CAST(1 AS Float64)+CAST(2 AS Decimal(12,3))",
            "CAST(1 AS Float64)+TRUE", "CAST(1 AS Float64)+missing", "CAST(1 AS Float64)+TRY_CAST('bad' AS Int8)"))
            assertEquals("UNKNOWN", type(typed("SELECT $expression")), expression)
        assertEquals("UNKNOWN", type(typed("WITH ((a,b) -> 'x') AS plus SELECT plus(CAST(1 AS Float32),2)")))
        assertEquals("UNKNOWN", type(typed("SELECT ns.plus(CAST(1 AS Float32),2)")))
    }

    @Test fun genericTypingCallerPoliciesOverwriteAndCacheContractsRemainUnchanged() {
        val sql = "SELECT CAST(1 AS Float32)+CAST(2 AS Int8)"
        val generic = annotateTypes(parseOne(sql, "clickhouse"), dialect = Dialects.CLICKHOUSE)
        assertEquals(DType.FLOAT, generic.find<Add>()?.type?.thisArg)
        fun annotator(metadata: Map<kotlin.reflect.KClass<out Expression>, TypingSpec>? = null, overwrite: kotlin.Boolean = true) =
            TypeAnnotator(MappingSchema(dialect = Dialects.CLICKHOUSE), expressionMetadata = metadata, overwriteTypes = overwrite).also {
                it.clickhouseNativeLiterals = true; it.clickhouseEngineVersion = "26.10.2.103"
            }
        val custom = parseOne(sql, "clickhouse")
        annotator(Dialects.CLICKHOUSE.expressionMetadata + (Add::class to TypingSpec.Returns(DType.VARCHAR))).annotate(custom)
        assertEquals(DType.VARCHAR, custom.find<Add>()?.type?.thisArg)
        val ownedLiteral = parseOne("SELECT -1+2", "clickhouse")
        annotator(Dialects.CLICKHOUSE.expressionMetadata + (Literal::class to TypingSpec.Returns(DType.DOUBLE))).annotate(ownedLiteral)
        assertEquals("UNKNOWN", type(ownedLiteral))
        val kept = parseOne(sql, "clickhouse")
        kept.find<Add>()!!.typeSlot = DataType(args("this" to DType.FLOAT, "nullable" to false))
        annotator(overwrite = false).annotate(kept); assertEquals("Float32", type(kept))
        val cached = parseOne(sql, "clickhouse")
        val typer = annotator()
        typer.annotate(cached); assertEquals("Float64", type(cached))
        val right = cached.find<Add>()!!.right as Cast
        right.set("to", (parseOne("SELECT CAST(2 AS Int128)", "clickhouse").find<Cast>()!!.args["to"] as DataType).copy())
        typer.uncache(cached); typer.annotate(cached); assertEquals("UNKNOWN", type(cached))
        right.set("to", (parseOne("SELECT CAST(2 AS Int32)", "clickhouse").find<Cast>()!!.args["to"] as DataType).copy())
        typer.clear(); typer.annotate(cached); assertEquals("Float64", type(cached))
    }

    @Test fun readOnlyDiagnosticsSourceSerdeCancellationAndVersionBoundariesStaySeparate() {
        val sql = "-- 😀\nSELECT 1.7976931348623157e308 + 1.7976931348623157e308"
        val ast = parseOne(sql, "clickhouse")
        val dump = Serde.dump(ast)
        assertTrue(validateClickhouseCompositions(ast, "26.10.2.103").errors.isEmpty())
        assertEquals(dump, Serde.dump(ast))
        val generated = ast.sql("clickhouse")
        annotateClickhouseSemanticTypes(ast, "26.10.2.103")
        assertEquals("Float64", type(ast), ast.walk().map { "${it::class.simpleName}:${it.name}:${it.type?.sql("clickhouse")}" }.toList().toString())
        assertEquals(generated, ast.sql("clickhouse"))
        assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression))
        assertTrue(clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103").errors.isEmpty())
        assertFailsWith<IllegalArgumentException> { annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.8.34.13") }
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103") { throw stop } }.exceptionOrNull())
        System.getProperty("brikk.clickhouseFloatingParserGapOut")?.let { path ->
            val cases = listOf("SELECT 1e0+2.0 AS v,toTypeName(v)",
                "SELECT 1.7976931348623157e308+1.7976931348623157e308 AS v,toTypeName(v)").map { source ->
                val parsed = typed(source)
                val projection = (parsed as Select).selects.first() as Alias
                buildJsonObject {
                    put("sql", source); put("type", type(parsed)); put("root_node", projection.thisArg!!::class.simpleName)
                    put("literal_texts", JsonArray(parsed.findAll<Literal>().map { JsonPrimitive(it.name) }.toList()))
                    put("generated", parsed.sql("clickhouse"))
                }
            }
            File(path).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("task", "CHSEM-022"); put("discovered_in", "CHSEM-021"); put("cases", JsonArray(cases))
            }) + "\n")
        }
    }

    @Test fun nativeMatrixMatches576TypesAndKeeps256RejectedPairsUnproved() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic/floating-add-sub-types")
        val input = Json.parseToJsonElement(File(folder, "input.json").readText()).jsonObject
        val native = Json.parseToJsonElement(File(folder, "26.10.2.103.json").readText()).jsonObject
        val expected = native.getValue("batches").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("types") }
        val rows = input.getValue("batches").jsonArray.map { element ->
            val batch = element.jsonObject
            val id = batch.getValue("id").jsonPrimitive.content
            val ast = typed(batch.getValue("sql").jsonPrimitive.content) as Select
            val actual = buildJsonObject {
                for (projection in ast.selects.filterIsInstance<Alias>()) {
                    val operation = projection.walk().first { clickhouseIntegerAddSubCall(it) != null }
                    put(projection.alias, operation.type?.sql("clickhouse") ?: "MISSING")
                }
            }
            assertEquals(36, actual.size); assertEquals(expected.getValue(id), actual, id)
            buildJsonObject { put("id", id); put("types", actual) }
        }
        val rejected = input.getValue("rejected").jsonArray.map { element ->
            val row = element.jsonObject
            val sql = row.getValue("sql").jsonPrimitive.content
            val ast = typed(sql)
            val operation = ast.walk().first { clickhouseIntegerAddSubCall(it) != null }
            assertTrue(operation.isType(DType.UNKNOWN), sql)
            assertTrue(clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103").errors.isEmpty())
            buildJsonObject { put("id", row.getValue("id")); put("type", "UNKNOWN") }
        }
        assertEquals(16, rows.size); assertEquals(256, rejected.size)
        System.getProperty("brikk.clickhouseFloatingAddSubTypesOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("batches", JsonArray(rows)); put("rejected", JsonArray(rejected))
            }) + "\n") }
    }

    @Test fun nativeFiniteWitnesses() = capture("floating-add-sub", "brikk.clickhouseFloatingAddSubOut", false)
    @Test fun nativeScriptWitnesses() = capture("floating-add-sub-script", "brikk.clickhouseFloatingAddSubScriptOut", true)

    private fun capture(name: String, property: String, script: kotlin.Boolean) {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "$name-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val units = annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103")
            assertTrue(units.all { it.expression != null && it.diagnostics.parseDiagnostics.isEmpty() }, sql)
            val types = units.mapNotNull { (it.expression as? Select)?.let(::type) }
            val expected = if (script) case.getValue("types").jsonArray.map { it.jsonPrimitive.content } else listOf(case.getValue("type").jsonPrimitive.content)
            assertEquals(expected, types, case.getValue("id").toString())
            val rules = units.flatMap { it.diagnostics.errors }.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules)
            for (unit in units) assertEquals(Serde.dump(unit.expression!!), Serde.dump(Serde.load(Serde.dump(unit.expression!!)) as Expression))
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql)
                put("generated", units.joinToString("; ", postfix = ";") { it.expression!!.sql("clickhouse") })
                if (script) put("types", JsonArray(types.map(::JsonPrimitive))) else put("result_type", types.single())
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty(property)?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
