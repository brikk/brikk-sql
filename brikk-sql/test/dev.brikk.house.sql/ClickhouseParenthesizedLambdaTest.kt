package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.*
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.String
import kotlin.test.*

class ClickhouseParenthesizedLambdaTest {
    @Test
    fun explicitOperandsAreLambdasRatherThanJsonExtraction() {
        for (sql in listOf(
            "SELECT arrayMap((x -> toString(x)) | (y -> upper(y)),CAST([1] AS Array(UInt8)))",
            "SELECT arrayMap(((x,y) -> multiply(x,y)) | (z -> toString(z)),CAST([2] AS Array(UInt8)),CAST([3] AS Array(UInt8)))",
            "SELECT arrayMap((((x -> lower(x)))) | ((y -> upper(y))),['A'])",
            "SELECT arrayMap((_1 -> toString(_1)) | (_1 -> upper(_1)),CAST([1] AS Array(UInt8)))",
            "SELECT arrayMap((`upper` -> toString(`upper`)) | upper,CAST([1] AS Array(UInt8)))")) {
            val ast = parseOne(sql, "clickhouse")
            assertNotNull(ast.find<ClickhouseComposition>(), sql)
            assertTrue(ast.findAll<Lambda>().any(), sql)
            assertNull(ast.find<JSONExtract>(), sql)
            assertEquals(ast, Serde.load(Serde.dump(ast)), sql)
            assertEquals(ast, parseOne(ast.sql("clickhouse"), "clickhouse"), sql)
            val typed = annotateClickhouseSemanticTypes(ast, "26.10.2.103")
            val map = typed.walk().first { it is Transform || it is Anonymous && it.name == "arrayMap" }
            assertEquals("Array(String)", map.type?.sql("clickhouse"), sql)
            assertTrue(typed.findAll<Lambda>().all { it.isType(DType.UNKNOWN) }, sql)
            assertTrue(typed.findAll<ClickhouseComposition>().all { it.isType(DType.UNKNOWN) }, sql)
            for (target in listOf("duckdb", "trino", "doris"))
                assertFailsWith<UnsupportedError> { SqlFragment(sql, "clickhouse").toExecutable(target) }
        }
    }

    @Test
    fun capturesShadowingAndFieldNamesKeepTheirLexicalScopesAndLineage() {
        val sql = "SELECT arrayMap((x -> CONCAT(x,t.s)) | (y -> upper(y)),t.a) AS v FROM t"
        val schema = mapOf("t" to mapOf("a" to "Array(String)", "s" to "String"))
        val ast = annotateTypes(parseOne(sql, "clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE)
        assertEquals("Array(String)", ast.find<Transform>()!!.type?.sql("clickhouse"))
        assertEquals(setOf("t.a", "t.s"), lineage("v", ast.sql("clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE).downstream.map { it.name }.toSet())
        val facts = RelationFacts(tables = mapOf("t" to RelationFact(listOf(ColumnFact("a", "Array(String)"), ColumnFact("s", "String")))), catalog = CatalogKnowledge.CLOSED)
        val scope = RelationScope.analyze(sql, "clickhouse", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        assertEquals(setOf("a", "s"), scope.references.filter { it.kind == ReferenceKind.COLUMN }.map { it.name }.toSet())
        val nested = annotateClickhouseSemanticTypes(parseOne("SELECT arrayMap((x -> arrayMap((x -> toString(x)) | (y -> upper(y)),CAST([2] AS Array(UInt8)))) | (z -> z),CAST([1] AS Array(UInt8)))", "clickhouse"), "26.10.2.103")
        assertEquals(listOf("UInt8", "UInt8", "String", "Array(String)"), nested.findAll<Lambda>(bfs = false).flatMap { it.expressionsArg.filterIsInstance<Identifier>() }.map { it.type?.sql("clickhouse") }.toList())
        assertEquals("Array(Array(String))", nested.find<Transform>()!!.type?.sql("clickhouse"))
    }

    @Test
    fun lambdaSyntaxAndDiagnosticRangesAreAuthoredUtf16Positions() {
        val sql = "-- 𝄞\r\nSELECT arrayMap((x -> toString(x)) | ((a,b) -> CONCAT(a,b)),CAST([1] AS Array(UInt8)))"
        val dialect = Dialects.CLICKHOUSE
        val parser = dialect.parser(ErrorLevel.RAISE).also { it.captureSourceRanges = true }
        val ast = parser.parse(Tokenizer(dialect.tokenizerConfig).tokenize(sql), sql).filterNotNull().single()
        val lambdas = ast.findAll<Lambda>(bfs = false).toList()
        assertEquals(2, lambdas.size)
        for (lambda in lambdas) {
            val range = assertNotNull(lambda.syntaxOrigin).range
            assertTrue(sql.substring(range.start, range.end).contains("->"))
        }
        val report = clickhouseSemanticDiagnostics(sql, sql.indices, "26.10.2.103")
        val error = report.errors.first { it.rule == ClickhouseSemanticRule.RIGHT_OPERAND_ARITY }.diagnostic
        assertEquals("(a,b) -> CONCAT(a,b)", sql.substring(error.start, error.end).trim())
        assertTrue(report.unknowns.none { it.reason == ClickhouseSemanticUnknownReason.PARSER_GAP })
        val script = "SELECT '𝄞';\r\n" + sql + ";"
        val shifted = clickhouseScriptSemanticDiagnostics(script, "26.10.2.103").last().errors.first { it.rule == ClickhouseSemanticRule.RIGHT_OPERAND_ARITY }.diagnostic
        assertEquals(error.start + "SELECT '𝄞';\r\n".length, shifted.start)
        assertEquals(error.end + "SELECT '𝄞';\r\n".length, shifted.end)
    }

    @Test
    fun jsonArrowsAndOrdinaryParenthesesRetainTheirDialectRules() {
        for (dialect in listOf("postgres", "duckdb", "mysql", "doris")) {
            for (arrow in listOf("->", "->>")) {
                val ast = parseOne("SELECT (doc $arrow 'k')", dialect)
                assertNull(ast.find<Lambda>(), "$dialect/$arrow")
                assertTrue(ast.walk().any { it is JSONExtract || it is JSONExtractScalar }, "$dialect/$arrow")
            }
        }
        assertNotNull(parseOne("SELECT ('{\"k\":1}' -> 'k')", "clickhouse").find<JSONExtract>())
        for (sql in listOf("SELECT (a+1)", "SELECT (a,b)", "SELECT ((a))", "SELECT (SELECT 1)", "SELECT (doc ->> 'k')")) {
            val ast = parseOne(sql, "clickhouse")
            assertNull(ast.find<Lambda>(), sql)
            assertEquals(ast, parseOne(ast.sql("clickhouse"), "clickhouse"), sql)
        }
    }
}
