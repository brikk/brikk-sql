package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.inferClickhouseCompositionResults
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClickhouseResultFixturesTest {
    private val json = Json { prettyPrint = true }
    private fun map(ast: Expression) = ast.walk().first { it is Transform || (it is Anonymous && it.name == "arrayMap") }
    private fun type(node: Expression) = if (node.isType(DType.UNKNOWN)) "UNKNOWN" else node.type?.sql("clickhouse") ?: "MISSING"

    @Test
    fun supportedResultsAndExplicitUnknownsMatchThePinnedWitnesses() {
        val fixture = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/result-cases.json").readText()).jsonObject
        val rows = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val input = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(input,"clickhouse")
            val before = ast.sql("clickhouse")
            annotateTypes(ast,dialect=Dialects.CLICKHOUSE)
            val result = type(map(ast))
            assertEquals(case.getValue("expected_type").jsonPrimitive.content,result,id)
            assertEquals(before,ast.sql("clickhouse"),id)
            val restored = Serde.load(Serde.dump(ast)) as Expression
            assertEquals(result,type(map(restored)),id)
            val report = inferClickhouseCompositionResults(ast,"26.10.2.103")
            assertTrue(ast.findAll<ClickhouseComposition>().all { it.isType(DType.UNKNOWN) },id)
            buildJsonObject {
                put("id",id)
                put("input_sql",input)
                if (case["brikk_parser_gap"] == null) put("generated",ast.sql("clickhouse"))
                else put("known_parser_gap",case.getValue("brikk_parser_gap"))
                put("result_type",result)
                put("rules",JsonArray(report.validation.errors.map { it.rule.name }.distinct().sorted().map(::JsonPrimitive)))
                put("unknown_reasons",JsonArray(report.validation.unknowns.map { it.reason.name }.distinct().sorted().map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseResultOut")?.let { path ->
            File(path).apply { parentFile?.mkdirs(); writeText(json.encodeToString(JsonArray.serializer(),JsonArray(rows))+"\n") }
        }
    }

    @Test
    fun everyPrimitivePairMatchesTheNativeMatrixRatherThanAnExpectedFormula() {
        val root = File(projectRoot(),"vendor/data/clickhouse-semantic/result-matrix")
        val input = Json.parseToJsonElement(File(root,"input.json").readText()).jsonObject
        val native = Json.parseToJsonElement(File(root,"26.10.2.103.json").readText()).jsonObject.getValue("types").jsonObject
        val ast = annotateTypes(parseOne(input.getValue("sql").jsonPrimitive.content,"clickhouse"),dialect=Dialects.CLICKHOUSE)
        val projections = (ast as Select).expressionsArg.filterIsInstance<Alias>()
        assertEquals(100,projections.size)
        val observed = buildJsonObject {
            for (projection in projections) {
                val actual = type(map(projection))
                assertEquals(native.getValue(projection.alias).jsonPrimitive.content,actual,projection.alias)
                put(projection.alias,actual)
            }
        }
        System.getProperty("brikk.clickhouseResultMatrixOut")?.let { path ->
            File(path).apply { parentFile?.mkdirs(); writeText(json.encodeToString(JsonObject.serializer(),observed)+"\n") }
        }
    }
}
