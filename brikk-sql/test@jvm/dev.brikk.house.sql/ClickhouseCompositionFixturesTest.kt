package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.ClickhouseCompositionUnknownReason
import dev.brikk.house.sql.optimizer.validateClickhouseCompositions
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Findings are rule evidence, not an editor/native acceptance verdict. */
class ClickhouseCompositionFixturesTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun compositionRulesMatchThePinnedCasesWithoutMutatingTheirAst() {
        val fixture = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/composition-cases.json").readText()).jsonObject
        val refinements = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/overload-refinements.json").readText()).jsonObject
            .getValue("rules").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        val observations = fixture.getValue("execution").jsonArray.map { row ->
            val case = row.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val input = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(input, "clickhouse")
            val before = Serde.dump(ast)
            val generated = ast.sql("clickhouse")
            val report = validateClickhouseCompositions(ast, "26.10.2.103")
            val rules = report.errors.map { it.rule.name }.distinct().sorted()
            val unknowns = report.unknowns.map { it.reason.name }.distinct().sorted()
            assertEquals((refinements[id]?.get("rules") ?: case.getValue("rules")).jsonArray.map { it.jsonPrimitive.content }.sorted(), rules, id)
            val requiredUnknowns = case["required_unknowns"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            assertTrue(unknowns.containsAll(requiredUnknowns), "$id: $unknowns")
            assertEquals(before, Serde.dump(ast), id)
            assertEquals(generated, ast.sql("clickhouse"), id)
            val old = validateClickhouseCompositions(ast, "26.8.34.13")
            assertTrue(old.errors.isEmpty(), id)
            assertEquals(listOf(ClickhouseCompositionUnknownReason.ENGINE_VERSION), old.unknowns.map { it.reason }, id)
            buildJsonObject {
                put("id", id)
                put("input_sql", input)
                put("engine_version", "26.10.2.103")
                put("rules", JsonArray(rules.map(::JsonPrimitive)))
                put("native_error_codes", JsonArray(report.errors.map { it.rule.nativeErrorCode }.distinct().sorted().map(::JsonPrimitive)))
                put("unknown_reasons", JsonArray(unknowns.map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseCompositionOut")?.let { path ->
            File(path).apply {
                parentFile?.mkdirs()
                writeText(json.encodeToString(JsonArray.serializer(), JsonArray(observations)) + "\n")
            }
        }
    }
}
