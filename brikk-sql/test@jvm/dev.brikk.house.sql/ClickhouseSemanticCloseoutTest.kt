package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseSemanticCloseoutTest {
    private fun type(ast: Expression): String = (ast as Select).selects.filterIsInstance<Expression>().first().let {
        if (it.isType(DType.UNKNOWN)) "UNKNOWN" else it.type?.sql("clickhouse") ?: "MISSING"
    }

    @Test fun frozenCorpusExercisesCombinedPublicStatementScriptAndDiagnosticContracts() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "closeout-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val refinements = Json.parseToJsonElement(File(folder, "multiplication-refinements.json").readText()).jsonObject.getValue("closeout").jsonObject
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val units = annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103")
            assertTrue(units.all { it.expression != null && it.diagnostics.parseDiagnostics.isEmpty() }, sql)
            assertEquals(clickhouseScriptSemanticDiagnostics(sql, "26.10.2.103"), units.map { it.diagnostics }, sql)
            val types = units.mapNotNull { (it.expression as? Select)?.let(::type) }
            assertEquals((refinements[case.getValue("id").jsonPrimitive.content] ?: case.getValue("types")).jsonArray.map { it.jsonPrimitive.content }, types, case.getValue("id").toString())
            val rules = units.flatMap { it.diagnostics.errors }.map { it.rule.name }.distinct().sorted()
            assertEquals(case.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, rules, sql)
            val generated = units.joinToString("; ", postfix = ";") { it.expression!!.sql("clickhouse") }
            val again = annotateClickhouseScriptSemanticTypes(generated, "26.10.2.103")
            assertEquals(types, again.mapNotNull { (it.expression as? Select)?.let(::type) })
            for (unit in units) assertEquals(Serde.dump(unit.expression!!), Serde.dump(Serde.load(Serde.dump(unit.expression!!)) as Expression))
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql); put("generated", generated)
                put("types", JsonArray(types.map(::JsonPrimitive))); put("rules", JsonArray(rules.map(::JsonPrimitive)))
                put("qualified", case.getValue("qualified"))
            }
        }
        System.getProperty("brikk.clickhouseCloseoutOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }

    @Test fun explicitModesOwnershipCancellationAndParserAcceptanceRemainSeparate() {
        val sql = "-- 😀\nSET sql_compatible_division_for_integers=1; SELECT 1/0"
        val result = annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103")
        assertEquals("UInt8", type(result.last().expression!!))
        assertEquals(ClickhouseSemanticRule.DIVISION_BY_ZERO, result.last().diagnostics.errors.single().rule)
        val range = sql.indexOf("SELECT") until sql.length
        val statement = annotateClickhouseSemanticTypes(parseOne(sql.substring(range), "clickhouse"), "26.10.2.103")
        assertEquals("UNKNOWN", type(statement))
        assertTrue(clickhouseSemanticDiagnostics(sql, range, "26.10.2.103").errors.isEmpty())
        val error = result.last().diagnostics.errors.single().diagnostic
        assertEquals(listOf(error), combineStatementDiagnostics(sql, range, listOf(error), NativeOutcome.Accepted).diagnostics)
        val second = annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103")
        assertNotSame(result.last().expression, second.last().expression)
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103") { throw stop } }.exceptionOrNull())
        assertFailsWith<IllegalArgumentException> { annotateClickhouseScriptSemanticTypes(sql, "26.8.34.13") }
    }

    @Test fun namedMultiplicationRefinementClosesTheFormerRootFallback() {
        val sql = "SELECT 1e0*(2e0+3.0)"
        val ast = annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103")
        assertNotNull(ast.find<Mul>())
        assertNotNull(ast.find<Add>())
        assertEquals("Float64", type(ast)) // CHSEM-025; old closeout captures are unchanged.
        val generic = annotateTypes(parseOne("SELECT 1+2", "clickhouse"), dialect = Dialects.CLICKHOUSE)
        assertEquals("Nullable(Int32)", type(generic))
    }

    @Test fun observedMissingCoverageIsPreservedWithoutFeatureExpansion() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "closeout-missing-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val refinements = Json.parseToJsonElement(File(folder, "multiplication-refinements.json").readText()).jsonObject.getValue("missing").jsonObject
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val units = annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103")
            assertTrue(units.all { it.expression != null && it.diagnostics.parseDiagnostics.isEmpty() }, sql)
            val types = units.mapNotNull { (it.expression as? Select)?.let(::type) }
            assertEquals(refinements[case.getValue("id").jsonPrimitive.content]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: listOf("UNKNOWN"), types, sql)
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql)
                put("generated", units.joinToString("; ", postfix = ";") { it.expression!!.sql("clickhouse") })
                put("types", JsonArray(types.map(::JsonPrimitive))); put("qualified", false)
                put("category", case.getValue("category")); put("reason", case.getValue("reason"))
                put("rules", JsonArray(units.flatMap { it.diagnostics.errors }.map { it.rule.name }.distinct().sorted().map(::JsonPrimitive)))
            }
        }
        System.getProperty("brikk.clickhouseCloseoutMissingOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
