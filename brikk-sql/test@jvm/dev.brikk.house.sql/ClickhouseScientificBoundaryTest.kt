package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseScientificBoundaryTest {
    private fun tokens(sql: String) = Dialects.CLICKHOUSE.tokenize(sql)
    private fun type(ast: Expression): String = (ast as Select).selects.filterIsInstance<Expression>().first().let {
        if (it.isType(DType.UNKNOWN)) "UNKNOWN" else it.type?.sql("clickhouse") ?: "MISSING"
    }
    @Test fun signsAfterExponentDigitsAreOperatorsAndExponentSignsStayInsideNumbers() {
        for ((source, expected) in mapOf(
            "1e0+2.0" to listOf("1e0", "+", "2.0"),
            "1E0-2.0" to listOf("1E0", "-", "2.0"),
            "1e-2+3.0" to listOf("1e-2", "+", "3.0"),
            "1e+2-3.0" to listOf("1e+2", "-", "3.0"),
            "1e0+-2.0" to listOf("1e0", "+", "-", "2.0"),
            "1.25E12+2.5e-1" to listOf("1.25E12", "+", "2.5e-1"))) {
            assertEquals(expected, tokens(source).map { it.text }, source)
            val ast = parseOne("SELECT $source", "clickhouse")
            assertTrue(ast.walk().any { it is Add || it is Sub }, source)
            annotateClickhouseSemanticTypes(ast, "26.10.2.103")
            assertEquals("Float64", type(ast), source)
        }
        assertEquals(listOf("1e0", ".", "5"), tokens("1e0.5").map { it.text })
    }

    @Test fun policyIsDialectScopedImmutableAndSurvivesOtherConfigBuilders() {
        val raw = ClickhouseTokenizerTables.CONFIG
        val legacy = Tokenizer(raw).tokenize("1e0+2.0").map { it.text }
        assertEquals(listOf("1e0+2.0"), legacy)
        assertEquals(legacy, Tokenizer().tokenize("1e0+2.0").map { it.text })
        assertFalse(raw.strictScientificNumberBoundaries)
        val native = raw.withScientificNumberBoundaries()
        assertTrue(native.strictScientificNumberBoundaries)
        for (copy in listOf(native.withKeywords(emptyMap()), native.withStringEscapes(), native.withCommentPolicy(),
            native.withLexicalRules(), native.withIdentifiers(emptyMap()), native.withByteStringPolicy(true), native.withInlineInsertData())) {
            assertEquals(listOf("1e0", "+", "2.0"), Tokenizer(copy).tokenize("1e0+2.0").map { it.text })
        }
        assertEquals(legacy, Tokenizer(native.withScientificNumberBoundaries(false)).tokenize("1e0+2.0").map { it.text })
        assertFalse(raw.strictScientificNumberBoundaries)
    }

    @Test fun positionsCommentsStringsFramingAndCancellationRemainSafe() {
        val sql = "-- 😀\r\nSELECT 1e0+2.0 AS v; SELECT '1e0+2.0' AS text"
        val all = tokens(sql)
        val plus = all.single { it.tokenType == TokenType.PLUS }
        assertEquals(sql.indexOf('+'), plus.start)
        assertEquals(plus.start, plus.end)
        assertEquals(2, plus.lineStart)
        assertEquals("1e0+2.0", all.single { it.tokenType == TokenType.STRING }.text)
        val units = annotateClickhouseScriptSemanticTypes(sql, "26.10.2.103")
        assertEquals(2, units.size); assertEquals("Float64", type(units.first().expression!!))
        val firstLiteral = units.first().expression!!.find<Literal>()!!
        assertEquals(sql.indexOf("1e0"), (firstLiteral.meta["start"] as Int) + units.first().diagnostics.start)
        assertEquals(sql.indexOf("1e0") + 2, (firstLiteral.meta["end"] as Int) + units.first().diagnostics.start)
        val tokenizer = Tokenizer(Dialects.CLICKHOUSE.tokenizerConfig)
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { tokenizer.tokenizeForEditor("SELECT " + "1".repeat(100000) + "e0+2.0") { throw stop } }.exceptionOrNull())
        assertEquals(listOf("1e0", "+", "2.0"), tokenizer.tokenize("1e0+2.0").map { it.text })
        assertTrue(SqlScriptSnapshot.parsePrefix("SELECT 1e0+", "clickhouse") {}.isEmpty())
    }

    @Test fun formerGapHasAddAstFloat64AndSerdeStableSource() {
        val old = Json.parseToJsonElement(File(projectRoot(), "vendor/data/clickhouse-semantic/floating-add-sub-parser-gap-brikk.json").readText()).jsonObject
        for (row in old.getValue("cases").jsonArray) {
            val sql = row.jsonObject.getValue("sql").jsonPrimitive.content
            val ast = parseOne(sql, "clickhouse")
            assertNotNull(ast.find<Add>())
            val generated = ast.sql("clickhouse")
            annotateClickhouseSemanticTypes(ast, "26.10.2.103")
            assertEquals("Float64", type(ast))
            assertEquals(generated, ast.sql("clickhouse"))
            assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression))
        }
    }

    @Test fun nativeScientificWitnesses() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic")
        val cases = Json.parseToJsonElement(File(folder, "scientific-number-cases.json").readText()).jsonObject.getValue("execution").jsonArray
        val rows = cases.map { element ->
            val case = element.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val ast = parseOne(sql, "clickhouse")
            val generated = ast.sql("clickhouse")
            annotateClickhouseSemanticTypes(ast, "26.10.2.103")
            assertEquals(case.getValue("type").jsonPrimitive.content, type(ast), sql)
            assertEquals(generated, ast.sql("clickhouse"))
            assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression))
            buildJsonObject {
                put("id", case.getValue("id")); put("input_sql", sql); put("generated", generated)
                put("result_type", type(ast)); put("rules", JsonArray(emptyList()))
                put("tokens", JsonArray(tokens(sql).map { token -> buildJsonObject {
                    put("kind", token.tokenType.name); put("text", token.text); put("start", token.start); put("end", token.end)
                } }))
            }
        }
        System.getProperty("brikk.clickhouseScientificOut")?.let { File(it).writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n") }
    }
}
