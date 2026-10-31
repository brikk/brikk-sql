package dev.brikk.house.sql

import dev.brikk.house.sql.ast.ClickhouseComposition
import dev.brikk.house.sql.ast.ClickhouseImplicitLambda
import dev.brikk.house.sql.ast.ClickhouseLambdaArgument
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Lambda
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.clickhouseCallableFacts
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Syntax/serialization gate and optional native-probe input, not a semantic validator. */
class ClickhouseSemanticFixturesTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun lambdaParameterTypesMatchTheNativeInputFixturesWithoutCertifyingCalls() {
        val fixture = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/lambda-input-cases.json").readText()).jsonObject
        val refinements = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/lambda-input-results-refinements.json").readText())
            .jsonObject.getValue("cases").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        fun type(node: Expression): String = if (node.isType(DType.UNKNOWN)) "UNKNOWN"
            else (node.type as? DataType)?.sql("clickhouse") ?: "MISSING"
        val observations = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val gap = case["brikk_parser_gap"]
            if (gap != null) return@map buildJsonObject {
                put("id", id)
                put("input_sql", case.getValue("sql"))
                put("known_parser_gap", gap)
            }
            val ast = parseOne(case.getValue("sql").jsonPrimitive.content, "clickhouse")
            assertIs<Select>(ast, id)
            val generated = ast.sql("clickhouse")
            assertEquals(ast, parseOne(generated, "clickhouse"), id)
            val annotated = annotateTypes(ast, dialect = Dialects.CLICKHOUSE)
            val formal = annotated.findAll<Lambda>(bfs = false).flatMap {
                it.expressionsArg.filterIsInstance<Identifier>().asSequence()
            }.map(::type).toList()
            val implicit = annotated.findAll<ClickhouseLambdaArgument>(bfs = false).map(::type).toList()
            assertEquals((refinements[id]?.get("formal_types") ?: case.getValue("formal_types")).jsonArray.map { it.jsonPrimitive.content }, formal, id)
            assertEquals((refinements[id]?.get("implicit_types") ?: case.getValue("implicit_types")).jsonArray.map { it.jsonPrimitive.content }, implicit, id)
            assertEquals(annotated, Serde.load(Serde.dump(annotated)), id)
            assertEquals(generated, annotated.sql("clickhouse"), id)
            buildJsonObject {
                put("id", id)
                put("input_sql", case.getValue("sql"))
                put("generated", generated)
                put("formal_types", JsonArray(formal.map(::JsonPrimitive)))
                put("implicit_types", JsonArray(implicit.map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseLambdaInputOut")?.let { path ->
            File(path).apply {
                parentFile?.mkdirs()
                writeText(json.encodeToString(JsonArray.serializer(), JsonArray(observations)) + "\n")
            }
        }
    }

    @Test
    fun callableFactsReferenceTheExactVersionedSources() {
        val root = projectRoot()
        val fixtures = Json.parseToJsonElement(File(root, "vendor/data/clickhouse-semantic/callables-cases.json").readText()).jsonObject
        val names = fixtures.getValue("execution").jsonArray.map { it.jsonObject.getValue("function").jsonPrimitive.content }.toSet()
        val sources = Json.parseToJsonElement(File(root, "vendor/data/clickhouse-semantic/callable-sources.json").readText()).jsonObject
        for (snapshot in sources.getValue("snapshots").jsonArray) {
            val version = snapshot.jsonObject.getValue("version").jsonPrimitive.content
            val ids = snapshot.jsonObject.getValue("sources").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
            for (name in names) {
                val facts = assertNotNull(clickhouseCallableFacts(name, version), "$version/$name")
                assertTrue(ids.containsAll(facts.sourceIds), "$version/$name: ${facts.sourceIds}")
            }
        }
    }

    @Test
    fun semanticFixturesRoundTripAndCaptureCurrentAnalysisWithoutCertifyingIt() {
        val input = File(projectRoot(), "vendor/data/clickhouse-semantic/cases.json")
        val fixture = Json.parseToJsonElement(input.readText()).jsonObject
        for (row in fixture.getValue("cases").jsonArray) {
            val case = row.jsonObject
            assertEquals(false, case.getValue("accept").jsonPrimitive.boolean)
            assertFails(case.getValue("id").jsonPrimitive.content) {
                parseOne(case.getValue("sql").jsonPrimitive.content, "clickhouse")
            }
        }
        val observations = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val ast = parseOne(case.getValue("sql").jsonPrimitive.content, "clickhouse")
            assertIs<Select>(ast, id)
            val generated = ast.sql("clickhouse")
            assertEquals(ast, parseOne(generated, "clickhouse"), id)
            assertEquals(ast, Serde.load(Serde.dump(ast)), id)
            val annotated = annotateTypes(ast.copy(), dialect = Dialects.CLICKHOUSE)
            buildJsonObject {
                put("id", id)
                put("root", ast::class.simpleName)
                put("generated", generated)
                put("composition_count", ast.findAll<ClickhouseComposition>().count())
                put("implicit_lambda_count", ast.findAll<ClickhouseImplicitLambda>().count())
                // Capture knowledge as it stands. UNKNOWN is not an acceptance verdict,
                // and later typing work may improve it without weakening this syntax gate.
                put("value_type", annotated.expressionsArg.filterIsInstance<Expression>().first().type?.sql("clickhouse"))
            }
        }
        System.getProperty("brikk.clickhouseSemanticOut")?.let { path ->
            File(path).apply {
                parentFile?.mkdirs()
                writeText(json.encodeToString(JsonArray.serializer(), JsonArray(observations)) + "\n")
            }
        }
    }
}
