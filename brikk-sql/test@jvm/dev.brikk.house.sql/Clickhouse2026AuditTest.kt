package dev.brikk.house.sql

import dev.brikk.house.sql.ast.ClickhouseStream
import dev.brikk.house.sql.ast.CombinedAggFunc
import dev.brikk.house.sql.ast.CombinedParameterizedAgg
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Self-contained fixture gate and an observation capture for the opt-in pinned native probe. */
class Clickhouse2026AuditTest {
    @Test
    fun fixedCasesStayStructuredAndCaptureCurrentCoverage() {
        val root = projectRoot()
        val input = System.getProperty("brikk.clickhouseAuditCases")?.let(::File)
            ?: File(root, "vendor/data/clickhouse-2026-audit/cases.json")
        val fixture = Json.parseToJsonElement(input.readText()).jsonObject
        val cases = fixture.getValue("cases").jsonArray + fixture.getValue("execution").jsonArray
        val observations = cases.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val sql = (case["brikk_sql"] ?: case.getValue("sql")).jsonPrimitive.content
            val source = case["brikk_dialect"]?.jsonPrimitive?.content ?: "clickhouse"
            val parsed = runCatching { parseOne(sql, source) }
            val fixed = case["task"]?.jsonPrimitive?.content == "CH26-002"
            val syntax = case["task"]?.jsonPrimitive?.content in setOf("CH26-004", "CH26-005", "CH26-006", "CH26-007", "CH26-008")
            if (syntax) {
                if (case["accept"]?.jsonPrimitive?.booleanOrNull != false) {
                    val ast = parsed.getOrThrow()
                    assertTrue(ast !is dev.brikk.house.sql.ast.Command, id)
                    assertEquals(ast, parseOne(ast.sql("clickhouse"), "clickhouse"), id)
                    assertEquals(ast, dev.brikk.house.sql.ast.Serde.load(dev.brikk.house.sql.ast.Serde.dump(ast)), id)
                } else assertTrue(parsed.isFailure, id)
            }
            if (fixed && case["accept"]?.jsonPrimitive?.booleanOrNull != false) {
                val ast = parsed.getOrThrow()
                assertEquals(ast, parseOne(ast.sql("clickhouse"), "clickhouse"), id)
                if (id.startsWith("CH26-S")) assertNotNull(ast.find<ClickhouseStream>(), id)
                else assertTrue(ast.find<CombinedAggFunc>() != null || ast.find<CombinedParameterizedAgg>() != null, id)
            } else if (fixed) {
                assertTrue(parsed.isFailure, id)
            }
            buildJsonObject {
                put("id", id)
                val ast = parsed.getOrNull()
                if (ast == null) put("error", parsed.exceptionOrNull()?.message)
                else {
                    put("root", ast::class.simpleName)
                    put("ast", ast.toString())
                    val generated = when {
                        case["lower_pipes"]?.jsonPrimitive?.booleanOrNull == true ->
                            dev.brikk.house.sql.shape.SqlFragment(sql, source).toExecutable("clickhouse").sql
                        source != "clickhouse" -> dev.brikk.house.sql.shape.SqlFragment(sql, source).transpileTo("clickhouse").sql
                        else -> ast.sql("clickhouse")
                    }
                    put("generated", generated)
                }
            }
        }
        val out = System.getProperty("brikk.clickhouseAuditOut")?.let(::File)
            ?: File(root, "build/reports/clickhouse-2026/brikk.json")
        out.parentFile?.mkdirs()
        out.writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(observations)) + "\n")
    }
}
