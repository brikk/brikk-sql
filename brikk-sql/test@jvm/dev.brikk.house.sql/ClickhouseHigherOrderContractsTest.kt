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

class ClickhouseHigherOrderContractsTest {
    @Test
    fun eachFamilyHasIndependentNativeResultsAndPublicRules() {
        val fixture = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/higher-order-cases.json").readText()).jsonObject
        val rows = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val sql = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(sql, "clickhouse")
            val generated = ast.sql("clickhouse")
            assertEquals(ast, Serde.load(Serde.dump(ast)), id)
            assertEquals(ast, parseOne(generated, "clickhouse"), id)
            annotateTypes(ast, dialect = Dialects.CLICKHOUSE)
            val projection = (ast as Select).selects.first() as Expression
            val type = if (projection.isType(DType.UNKNOWN)) "UNKNOWN" else projection.type?.sql("clickhouse") ?: "MISSING"
            assertEquals(case.getValue("type").jsonPrimitive.content, type, id)
            assertEquals(generated, ast.sql("clickhouse"), id)
            assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression), id)
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
            assertTrue(report.parseDiagnostics.isEmpty(), id)
            val rules = report.errors.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }.sorted(), rules, "$id: $report")
            assertTrue(report.errors.all { it.diagnostic.end <= sql.length && it.diagnostic.start >= 0 }, id)
            buildJsonObject {
                put("id", id); put("input_sql", sql); put("generated", generated); put("result_type", type)
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
                put("unknowns", JsonArray(report.unknowns.map { it.reason.name }.distinct().sorted().map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseHigherOrderOut")?.let { path -> File(path).apply {
            parentFile?.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n")
        } }
    }

    @Test
    fun bindingCaptureShadowingPoliciesAndCancellationRemainIndependent() {
        val sql = "SELECT arrayFilter(x -> x>1,t.a) AS v FROM t"
        val schema = mapOf("t" to mapOf("a" to "Array(UInt8)"))
        val ast = annotateTypes(parseOne(sql, "clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE)
        assertEquals("UInt8", ast.find<Lambda>()!!.expressionsArg.filterIsInstance<Identifier>().single().type?.sql("clickhouse"))
        assertEquals(setOf("t.a"), lineage("v", sql, schema = schema, dialect = Dialects.CLICKHOUSE).downstream.map { it.name }.toSet())
        val nested = annotateTypes(parseOne("SELECT arrayMap(x -> arraySort(x -> lower(x),CAST(['A'] AS Array(String))),CAST([1] AS Array(UInt8)))", "clickhouse"), dialect = Dialects.CLICKHOUSE)
        assertEquals(listOf("UInt8", "String"), nested.findAll<Lambda>(bfs = false).flatMap { it.expressionsArg.filterIsInstance<Identifier>() }.map { it.type?.sql("clickhouse") }.toList())
        val overridden = parseOne("SELECT arrayFilter(x -> x,CAST([1] AS Array(UInt8)))", "clickhouse")
        annotateTypes(overridden, dialect = Dialects.CLICKHOUSE, expressionMetadata = Dialects.CLICKHOUSE.expressionMetadata +
            (ArrayFilter::class to TypingSpec.Returns(DType.DOUBLE)))
        assertTrue(overridden.find<ArrayFilter>()!!.isType(DType.DOUBLE))
        val unresolved = "SELECT arrayFilter(trim | upper,['x']) FROM t"
        val unknown = clickhouseSemanticDiagnostics(unresolved, unresolved.indices, "26.10.2.103")
        assertTrue(unknown.errors.isEmpty())
        val wrongCase = "SELECT ARRAYEXISTS(x -> x,CAST(['a'] AS Array(String)))"
        val unresolvedCase = clickhouseSemanticDiagnostics(wrongCase, wrongCase.indices, "26.10.2.103")
        assertTrue(unresolvedCase.errors.isEmpty())
        assertTrue(unresolvedCase.unknowns.any { it.reason == ClickhouseSemanticUnknownReason.UNRESEARCHED_CALLABLE })
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103") { throw stop } }.exceptionOrNull())
    }

    @Test
    fun tupleUnpackingAndMissingInputsNeverBecomeFalseArityProofs() {
        for (sql in listOf("SELECT arraySort((x,y) -> y,CAST([('a',1)] AS Array(Tuple(String,UInt8))))",
            "SELECT arrayFilter((x,y) -> y,CAST([('a',1)] AS Array(Tuple(String,UInt8))))")) {
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
            assertTrue(report.errors.isEmpty(), report.toString())
            // CHSEM-009 now proves these explicit tuple declarations and their bindings.
        }
        for ((sql, rule) in listOf(
            "SELECT arrayExists(x -> x,CAST(['x'] AS Array(String)))" to ClickhouseSemanticRule.PREDICATE_TYPE,
            "SELECT arrayAll(x -> x,CAST([1] AS Array(UInt16)))" to ClickhouseSemanticRule.PREDICATE_TYPE,
            "SELECT arraySort((x,y) -> y,CAST([1] AS Array(UInt8)))" to ClickhouseSemanticRule.HIGHER_ORDER_ARITY,
            "SELECT arraySort(1)" to ClickhouseSemanticRule.ARRAY_INPUT_TYPE)) {
            assertTrue(clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103").errors.any { it.rule == rule }, sql)
        }
        val mapSql = "SELECT mapApply((k,v) -> (k,CONCAT(v,t.s)),t.m) AS result FROM t"
        val schema = mapOf("t" to mapOf("m" to "Map(String,String)", "s" to "String"))
        val ast = annotateTypes(parseOne(mapSql, "clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE)
        assertEquals("Map(String, String)", ((ast as Select).selects.single() as Expression).type?.sql("clickhouse"))
        assertEquals(setOf("t.m", "t.s"), lineage("result", mapSql, schema = schema, dialect = Dialects.CLICKHOUSE).downstream.map { it.name }.toSet())
    }
}
