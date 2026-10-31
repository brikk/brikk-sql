package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateClickhouseSemanticTypes
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseParenthesizedLambdaFixturesTest {
    @Test
    fun originalGeneratedAstTypesAndDiagnosticsShareTheFixedLambdaStructure() {
        val fixture = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/paren-lambda-cases.json").readText()).jsonObject
        for (row in fixture.getValue("cases").jsonArray) assertFails(row.jsonObject.getValue("id").jsonPrimitive.content) {
            parseOne(row.jsonObject.getValue("sql").jsonPrimitive.content, "clickhouse")
        }
        val rows = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val sql = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(sql, "clickhouse")
            assertNotNull(ast.find<Lambda>(), id)
            assertNull(ast.find<JSONExtract>(), id)
            val generated = ast.sql("clickhouse")
            assertEquals(ast, parseOne(generated, "clickhouse"), id)
            assertEquals(ast, Serde.load(Serde.dump(ast)), id)
            annotateClickhouseSemanticTypes(ast, "26.10.2.103")
            val projection = (ast as Select).selects.first() as Expression
            val type = if (projection.isType(DType.UNKNOWN)) "UNKNOWN" else projection.type?.sql("clickhouse") ?: "MISSING"
            assertEquals(case.getValue("type").jsonPrimitive.content, type, id)
            assertEquals(generated, ast.sql("clickhouse"), id)
            assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression), id)
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
            assertTrue(report.parseDiagnostics.isEmpty(), id)
            assertTrue(report.unknowns.none { it.reason == ClickhouseSemanticUnknownReason.PARSER_GAP }, "$id: $report")
            val rules = report.errors.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }.sorted(), rules, id)
            buildJsonObject {
                put("id", id); put("input_sql", sql); put("generated", generated); put("result_type", type)
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
                put("unknowns", JsonArray(report.unknowns.map { it.reason.name }.distinct().sorted().map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseParenOut")?.let { path -> File(path).apply {
            parentFile?.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n")
        } }
        // Optional review output, never an expectation source: exact historical tests still gate the refinements.
        System.getProperty("brikk.clickhouseParenReviewOut")?.let { path ->
            val cases = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/composition-cases.json").readText()).jsonObject.getValue("execution").jsonArray
            val reviewed = cases.map { row ->
                val case = row.jsonObject; val sql = case.getValue("sql").jsonPrimitive.content
                val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
                buildJsonObject {
                    put("id", case.getValue("id")); put("rules", JsonArray(report.errors.map { it.rule.name }.distinct().sorted().map(::JsonPrimitive)))
                    put("unknown_reasons", JsonArray(report.unknowns.map { it.reason.name }.distinct().sorted().map(::JsonPrimitive)))
                }
            }
            File(path).writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(reviewed)) + "\n")
        }
    }
}
