package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseOverloadTypingTest {
    private fun result(sql: String): Expression = annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103")
    private fun type(ast: Expression): String {
        val value = if (ast is Select) ast.selects.first() as Expression else ast
        return if (value.isType(DType.UNKNOWN)) "UNKNOWN" else value.type?.sql("clickhouse") ?: "MISSING"
    }

    @Test
    fun nativeLiteralCompositeAndOverloadWitnessesMatchWithoutCertifyingExecution() {
        val fixture = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/overload-cases.json").readText()).jsonObject
        val rows = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val sql = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(sql, "clickhouse")
            val generated = ast.sql("clickhouse")
            assertEquals(ast, parseOne(generated, "clickhouse"), id)
            annotateClickhouseSemanticTypes(ast, "26.10.2.103")
            val actual = type(ast)
            assertEquals(case.getValue("type").jsonPrimitive.content, actual, id)
            assertEquals(generated, ast.sql("clickhouse"), id)
            assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression), id)
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
            assertTrue(report.parseDiagnostics.isEmpty(), id)
            val rules = report.errors.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }.sorted(), rules, "$id: $report")
            buildJsonObject {
                put("id", id); put("input_sql", sql); put("generated", generated); put("result_type", actual)
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
                put("unknowns", JsonArray(report.unknowns.map { it.reason.name }.distinct().sorted().map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseOverloadOut")?.let { path -> File(path).apply {
            parentFile?.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n")
        } }
    }

    @Test
    fun integerLiteralBoundariesAndSignedJoinsNeverUseGenericHints() {
        for ((literal, expected) in listOf("0" to "UInt8", "255" to "UInt8", "256" to "UInt16", "65535" to "UInt16",
            "65536" to "UInt32", "4294967295" to "UInt32", "4294967296" to "UInt64", "18446744073709551615" to "UInt64",
            "-1" to "Int8", "-128" to "Int8", "-129" to "Int16", "-32768" to "Int16", "-32769" to "Int32",
            "-2147483648" to "Int32", "-2147483649" to "Int64", "-9223372036854775808" to "Int64")) {
            assertEquals("Array($expected)", type(result("SELECT arrayMap(x -> x,[$literal])")), literal)
        }
        assertEquals("Array(Int32)", type(result("SELECT arrayMap(x -> x,[65535,-1])")))
        for (array in listOf("[]", "[NULL]", "[NULL,[1]]", "[18446744073709551615,-1]", "[1,1.5]", "[-0]"))
            assertEquals("UNKNOWN", type(result("SELECT arrayMap(x -> x,$array)")), array)
        val sql = "SELECT arrayMap(x -> x,[1])"
        assertEquals("UNKNOWN", type(annotateTypes(parseOne(sql, "clickhouse"), dialect = Dialects.CLICKHOUSE)))
        assertEquals("Array(UInt8)", type(result(sql)))
    }

    @Test
    fun literalWidthsMatchIndependentNativeBoundaryObservations() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic/literal-types")
        val input = Json.parseToJsonElement(File(folder, "input.json").readText()).jsonObject
        val ast = result(input.getValue("sql").jsonPrimitive.content) as Select
        val observed = buildJsonObject {
            for (projection in ast.selects.filterIsInstance<Alias>()) put(projection.alias, projection.find<Transform>()!!.type!!.sql("clickhouse"))
        }
        assertEquals(16, observed.size)
        for (version in listOf("26.8.34.13", "26.10.2.103")) {
            val native = Json.parseToJsonElement(File(folder, "$version.json").readText()).jsonObject.getValue("types").jsonObject
            assertEquals(native, observed, version)
        }
        System.getProperty("brikk.clickhouseLiteralTypesOut")?.let { path -> File(path).apply {
            parentFile?.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), observed) + "\n")
        } }
    }

    @Test
    fun unsupportedSettingsCallablesAndMixedDecimalsStayUnknownAndPinsAreMandatory() {
        for (sql in listOf(
            "SELECT arrayMap(brikkMissingSemanticFunction | upper,['x'])",
            "SELECT arrayMap(x -> lower(CAST(NULL AS Nullable(String))),['x'])",
            "SELECT arrayMap(x -> CAST('a' AS LowCardinality(String)),[1])",
            "SELECT arrayMap(x -> x,CAST([1] AS Array(Dynamic)))",
            "SELECT arrayMap(multiply,CAST([1] AS Array(Decimal(5,2))),CAST([1] AS Array(Int8)))",
            "SELECT arrayMap(multiply,CAST([1] AS Array(Decimal(5,2))),CAST([1] AS Array(Float64)))"))
            assertEquals("UNKNOWN", type(result(sql)), sql)
        assertFailsWith<IllegalArgumentException> { annotateClickhouseSemanticTypes(parseOne("SELECT 1", "clickhouse"), "26.8.34.13") }
        assertFailsWith<IllegalArgumentException> { annotateClickhouseSemanticTypes(parseOne("SELECT 1", "clickhouse"), "26.10.2.103", MappingSchema(dialect = Dialects.POSTGRES)) }
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { annotateClickhouseSemanticTypes(parseOne("SELECT 1", "clickhouse"), "26.10.2.103") { throw stop } }.exceptionOrNull())
        val badNullable = "SELECT arrayMap(lower | upper,CAST([NULL] AS Array(Nullable(UInt8))))"
        assertEquals(listOf(ClickhouseSemanticRule.INPUT_TYPE), clickhouseSemanticDiagnostics(badNullable, badNullable.indices, "26.10.2.103").errors.map { it.rule })
        val boolAlias = "SELECT arrayExists(x -> x,CAST([1] AS Array(Bool)))"
        assertTrue(clickhouseSemanticDiagnostics(boolAlias, boolAlias.indices, "26.10.2.103").errors.isEmpty())
        val missingData = "SELECT arrayExists(x -> CAST(1 AS UInt8),arr)"
        assertEquals("UNKNOWN", type(result(missingData)))
        val shadowed = "WITH ((fn,a) -> 1) AS arrayFilter SELECT arrayFilter(x -> x,CAST([1] AS Array(UInt8)))"
        assertEquals("UNKNOWN", type(result(shadowed)))
        val suspicious = result("SELECT arrayMap(x -> toString(x),CAST([1] AS Array(LowCardinality(UInt8))))")
        assertTrue(suspicious.find<Lambda>()!!.expressionsArg.filterIsInstance<Identifier>().single().isType(DType.UNKNOWN))
        val opaqueMap = "SELECT mapApply((k,v) -> (k,v),t.m) FROM t"
        val partial = MappingSchema(mapOf("t" to mapOf("m" to "Map(String,Dynamic)")), dialect = Dialects.CLICKHOUSE)
        assertTrue(clickhouseSemanticDiagnostics(opaqueMap, opaqueMap.indices, "26.10.2.103", partial).errors.isEmpty())
    }

    @Test
    fun recursiveLowCardinalityRemovalAndTupleFieldsDoNotMutateSchemaFacts() {
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Array(Tuple(LowCardinality(String), UInt16))")), dialect = Dialects.CLICKHOUSE)
        val before = schema.getColumnType("t", "a").copy()
        val sql = "SELECT arrayMap((s,n) -> s,t.a) AS v FROM t"
        val ast = annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103", schema)
        assertEquals("Array(String)", type(ast))
        assertEquals(before, schema.getColumnType("t", "a"))
        assertEquals(listOf("String", "UInt16"), ast.find<Lambda>()!!.expressionsArg.filterIsInstance<Identifier>().map { it.type?.sql("clickhouse") })
    }
}
