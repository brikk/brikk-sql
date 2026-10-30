package dev.brikk.house.sql

import dev.brikk.house.sql.ast.ClickhouseComposition
import dev.brikk.house.sql.ast.ClickhouseImplicitLambda
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

/** Syntax/serialization gate and optional native-probe input, not a semantic validator. */
class ClickhouseSemanticFixturesTest {
    private val json = Json { prettyPrint = true }

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
