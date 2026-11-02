package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseProjectionLiteralTypesTest {
    private fun native(sql: String) = annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103")
    private fun type(ast: Expression): String {
        val node = if (ast is Select) ast.selects.filterIsInstance<Expression>().first() else ast
        return if (node.isType(DType.UNKNOWN)) "UNKNOWN" else node.type?.sql("clickhouse") ?: "MISSING"
    }

    @Test fun nativeScalarProjectionTypesIncludeExactNullabilityAndNumericSignBoundaries() {
        val cases = mapOf("0" to "UInt8", "-0" to "UInt8", "255" to "UInt8", "256" to "UInt16",
            "4294967296" to "UInt64", "18446744073709551615" to "UInt64", "18446744073709551616" to "Float64",
            "-128" to "Int8", "-129" to "Int16", "-9223372036854775808" to "Int64", "-9223372036854775809" to "Float64",
            "1.25" to "Float64", "-0.0" to "Float64", "1e-400" to "Float64", "'hello'" to "String",
            "TRUE" to "Bool", "FALSE" to "Bool", "NULL" to "Nullable(Nothing)")
        for ((literal, expected) in cases) {
            for (projection in listOf(literal, "$literal AS v", "(($literal)) AS v"))
                assertEquals(expected, type(native("SELECT $projection")), projection)
        }
        assertEquals("String", type(annotateClickhouseSemanticTypes(Literal.string("x"), "26.10.2.103")))
        assertEquals("Nullable(Nothing)", type(annotateClickhouseSemanticTypes(Null(), "26.10.2.103")))
        assertEquals("UNKNOWN", type(native("SELECT 1e309")))
        assertEquals("UNKNOWN", type(native("SELECT ${"0".repeat(320)}")))
    }

    @Test fun genericTypingOtherDialectsAndCompoundArithmeticKeepTheirPolicies() {
        val generic = annotateTypes(parseOne("SELECT 'x'", "clickhouse"), dialect = Dialects.CLICKHOUSE)
        assertEquals("Nullable(String)", type(generic))
        val reused = native("SELECT 'x'")
        annotateTypes(reused, dialect = Dialects.CLICKHOUSE)
        assertEquals("Nullable(String)", type(reused))
        assertEquals(DType.INT, (annotateTypes(parseOne("SELECT 7", "clickhouse"), dialect = Dialects.CLICKHOUSE) as Select).selects.filterIsInstance<Expression>().first().type?.thisArg)
        val arithmetic = "SELECT 1+2"
        assertEquals(DType.INT, (annotateTypes(parseOne(arithmetic, "clickhouse"), dialect = Dialects.CLICKHOUSE) as Select).selects.filterIsInstance<Expression>().first().type?.thisArg)
        assertEquals("UInt16", type(native(arithmetic))) // CHSEM-020's separate native arithmetic proof.
        val duck = parseOne("SELECT 'x'", "duckdb")
        annotateTypes(duck, dialect = Dialects.DUCKDB)
        assertNull((duck as Select).selects.filterIsInstance<Expression>().first().type?.args?.get("nullable"))
        assertEquals("UNKNOWN", type(native("SELECT CAST(NULL AS Nullable(Int8))/CAST(2 AS Int8) SETTINGS sql_compatible_division_for_integers=1")))
    }

    @Test fun scriptEntryPointDerivedQueriesSourceMetadataAndSerdeStayConsistent() {
        val sql = "-- 😀\nSELECT 'x' AS v; SELECT (NULL) AS v; SET sql_compatible_division_for_integers=1; SELECT 7/2"
        val result = annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103")
        assertEquals(listOf("String", "Nullable(Nothing)", "UInt8"), result.mapNotNull { (it.expression as? Select)?.let(::type) })
        assertEquals("UInt16", type(native("SELECT q.v FROM (SELECT 256 AS v) q")))
        assertEquals("String", type(native("SELECT q.v FROM (SELECT 'x' AS v) q")))
        assertEquals("Nullable(Nothing)", type(native("SELECT q.v FROM (SELECT NULL AS v) q")))
        val originals = Dialects.CLICKHOUSE.parse(sql).filterNotNull()
        for ((original, typed) in originals.zip(result)) {
            assertEquals(original.sql("clickhouse"), typed.expression!!.sql("clickhouse"))
            assertEquals(Serde.dump(typed.expression!!), Serde.dump(Serde.load(Serde.dump(typed.expression!!)) as Expression))
        }
    }

    @Test fun callerOwnedPoliciesOverwriteAndCacheInvalidationRemainSupported() {
        fun annotator(metadata: Map<kotlin.reflect.KClass<out Expression>, TypingSpec>? = null, overwrite: kotlin.Boolean = true) =
            TypeAnnotator(MappingSchema(dialect = Dialects.CLICKHOUSE), expressionMetadata = metadata, overwriteTypes = overwrite).also {
                it.clickhouseNativeLiterals = true; it.clickhouseEngineVersion = "26.10.2.103"
            }
        val overridden = parseOne("SELECT 7", "clickhouse")
        annotator(Dialects.CLICKHOUSE.expressionMetadata + (Literal::class to TypingSpec.Returns(DType.BIGINT))).annotate(overridden)
        assertEquals(DType.BIGINT, overridden.find<Literal>()!!.type?.thisArg)
        val kept = parseOne("SELECT 7", "clickhouse")
        kept.find<Literal>()!!.typeSlot = DataType(args("this" to DType.DOUBLE, "nullable" to false))
        annotator(overwrite = false).annotate(kept)
        assertEquals("Float64", type(kept))
        val ast = parseOne("SELECT 7", "clickhouse")
        val cached = annotator()
        cached.annotate(ast); assertEquals("UInt8", type(ast))
        ast.find<Literal>()!!.set("this", "256")
        cached.uncache(ast); cached.annotate(ast); assertEquals("UInt16", type(ast))
        cached.clear(); cached.annotate(ast); assertEquals("UInt16", type(ast))
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { annotateClickhouseSemanticTypes(parseOne("SELECT 'x'", "clickhouse"), "26.10.2.103") { throw stop } }.exceptionOrNull())
    }

    @Test fun allProjectionMatrixTypesMatchIndependentNativeObservations() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic/projection-literal-types")
        val input = Json.parseToJsonElement(File(folder, "input.json").readText()).jsonObject
        val expected = Json.parseToJsonElement(File(folder, "26.10.2.103.json").readText()).jsonObject.getValue("batches").jsonArray
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("types") }
        val rows = input.getValue("batches").jsonArray.map { element ->
            val batch = element.jsonObject
            val id = batch.getValue("id").jsonPrimitive.content
            val ast = native(batch.getValue("sql").jsonPrimitive.content) as Select
            val actual = buildJsonObject {
                for (projection in ast.selects.filterIsInstance<Alias>().filter { it.alias.startsWith("p") })
                    put(projection.alias, projection.type?.sql("clickhouse") ?: "MISSING")
            }
            assertEquals(42, actual.size)
            assertEquals(expected.getValue(id), actual, id)
            buildJsonObject { put("id", id); put("types", actual) }
        }
        System.getProperty("brikk.clickhouseProjectionTypesOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }

    @Test fun finiteProjectionWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "projection-literal-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(sql, "clickhouse")
            val generated = ast.sql("clickhouse")
            annotateClickhouseSemanticTypes(ast, "26.10.2.103")
            val actual = type(ast)
            assertEquals(case.getValue("type").jsonPrimitive.content, actual, sql)
            assertEquals(generated, ast.sql("clickhouse"))
            assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression))
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql); put("generated", generated); put("result_type", actual)
                put("rules", JsonArray(emptyList()))
            }
        }
        System.getProperty("brikk.clickhouseProjectionLiteralsOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }

    @Test fun nativeWholeScriptProjectionWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "projection-literal-script-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val result = annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103")
            assertTrue(result.all { it.expression != null && it.diagnostics.parseDiagnostics.isEmpty() })
            val types = result.mapNotNull { (it.expression as? Select)?.let(::type) }
            val rules = result.flatMap { it.diagnostics.errors }.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("types").jsonArray.map { it.jsonPrimitive.content }, types, sql)
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules, sql)
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql)
                put("generated", result.joinToString("; ", postfix = ";") { it.expression!!.sql("clickhouse") })
                put("types", JsonArray(types.map(::JsonPrimitive))); put("rules", JsonArray(rules.map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseProjectionLiteralsScriptOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
