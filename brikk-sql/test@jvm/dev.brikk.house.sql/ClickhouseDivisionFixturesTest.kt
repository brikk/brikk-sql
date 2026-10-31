package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.diagnostics.clickhouseSemanticDiagnostics
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateClickhouseSemanticTypes
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseDivisionFixturesTest {
    @Test
    fun conditionalTypesAndValueErrorsStaySeparateAcrossThePinnedFixtures() {
        val fixture = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/division-cases.json").readText()).jsonObject
        val rows = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val sql = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(sql, "clickhouse")
            val generated = ast.sql("clickhouse")
            assertEquals(ast, parseOne(generated, "clickhouse"), id)
            annotateClickhouseSemanticTypes(ast, "26.10.2.103")
            val projection = (ast as Select).selects.first() as Expression
            val type = if (projection.isType(DType.UNKNOWN)) "UNKNOWN" else projection.type?.sql("clickhouse") ?: "MISSING"
            assertEquals(case.getValue("type").jsonPrimitive.content, type, id)
            assertEquals(generated, ast.sql("clickhouse"), id)
            assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression), id)
            val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
            assertTrue(report.parseDiagnostics.isEmpty(), id)
            val rules = report.errors.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }.sorted(), rules, id)
            buildJsonObject {
                put("id", id); put("input_sql", sql); put("generated", generated); put("result_type", type)
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
                put("unknowns", JsonArray(report.unknowns.map { it.reason.name }.distinct().sorted().map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseDivisionOut")?.let { path -> File(path).apply {
            parentFile?.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n")
        } }
    }
}
