package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** DDB-001: portable template markers are not DuckDB prefix-alias declarations. */
class DuckdbTemplateParameterTest {
    @Test
    fun bareCastAndParenthesizedColonProjectionsKeepTheirTemplateContract() {
        for ((source, name, label) in listOf(
            Triple("SELECT :n AS n", "n", "n"),
            Triple("SELECT :value AS result", "value", "result"),
            Triple("SELECT :n::INTEGER AS n", "n", "n"),
            Triple("SELECT (:n) AS n", "n", "n"),
            Triple("SELECT ((:n)) AS n", "n", "n"),
            Triple("SELECT :n + 1 AS n", "n", "n"),
        )) {
            val fragment = SqlFragment(source, "duckdb")
            val select = assertIs<Select>(fragment.ast, source)
            assertEquals(label, assertIs<Alias>(select.expressionsArg.single(), source).alias)
            assertEquals(name, select.findAll<Placeholder>().single().name)
            assertEquals(listOf(name), fragment.scalarParams.map { it.name })
            assertEquals(ParamStyle.NAMED_COLON, fragment.scalarParams.single().style)
            val rendered = fragment.toSourcePreservingExecutable()
            assertEquals(source, rendered.sql)
            assertTrue(rendered.diagnostics.isEmpty(), source)
            val occurrence = rendered.parameterOccurrences.single()
            assertEquals(":$name", source.substring(occurrence.range.start, occurrence.range.end))
            val nameRange = occurrence.nameRange!!
            assertEquals(name, source.substring(nameRange.start, nameRange.end))
            // Ordinary generation deliberately writes DuckDB's native marker spelling.
            assertTrue("\$$name" in select.sql("duckdb"), source)
        }
    }

    @Test
    fun realPrefixAliasesStillParseWithNamedTemplateValues() {
        for ((source, label) in listOf(
            "SELECT answer: :n" to "answer",
            "SELECT \"odd alias\": :n" to "odd alias",
            "SELECT current_date: :n" to "current_date",
        )) {
            val fragment = SqlFragment(source, "duckdb")
            val alias = assertIs<Alias>(assertIs<Select>(fragment.ast).expressionsArg.single())
            assertEquals(label, alias.alias, source)
            assertEquals("n", assertIs<Placeholder>(alias.thisArg).name, source)
            assertEquals(source, fragment.toSourcePreservingExecutable().sql)
        }
        assertEquals("SELECT 1 AS answer", parseOne("SELECT answer: 1", "duckdb").sql("duckdb"))
    }

    @Test
    fun repeatedNestedAndPipeProjectionsExposeOnlyActualParameterIntervals() {
        for (source in listOf(
            "SELECT :n AS n, :n::INTEGER AS again",
            "SELECT :n AS n FROM (SELECT (:n) AS nested) AS input",
            "FROM (SELECT :n AS n) AS input |> SELECT :n AS again",
            "WITH input AS (SELECT :n AS n) SELECT :n AS again FROM input",
            "select /* 😀 :n */ :n::INTEGER as n, '(:n)' as text -- :n",
        )) {
            val fragment = SqlFragment(source, "duckdb")
            val parsedOccurrences = fragment.ast.findAll<Placeholder>().toList()
            assertTrue(parsedOccurrences.isNotEmpty(), source)
            val result = fragment.toSourcePreservingExecutable()
            assertEquals(parsedOccurrences.size, result.parameterOccurrences.size, source)
            for (occurrence in result.parameterOccurrences) {
                assertEquals(":n", source.substring(occurrence.range.start, occurrence.range.end), source)
            }
        }
    }

    @Test
    fun literalsNumbersAndStatementKeywordsDoNotBecomePrefixAliases() {
        for (source in listOf("SELECT 'label': 1", "SELECT 1: 2", "SELECT SELECT: 1")) {
            assertFailsWith<ParseError>(source) { parseOne(source, "duckdb") }
        }
    }
}
