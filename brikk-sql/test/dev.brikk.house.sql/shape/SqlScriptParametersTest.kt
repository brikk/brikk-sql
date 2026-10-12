package dev.brikk.house.sql.shape

import dev.brikk.house.sql.parser.SyntacticRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqlScriptParametersTest {

    private fun names(sql: String, dialect: String = "doris"): List<String?> =
        SqlScriptParameters.parse(sql, dialect).occurrences.map { it.name }

    @Test
    fun findsBindsAcrossStatementsWithExactRanges() {
        val sql = ";SELECT :first;;SELECT :second, :first; "
        val result = SqlScriptParameters.parse(sql, "doris")
        assertEquals(listOf("first", "second", "first"), result.occurrences.map { it.name })
        assertEquals(SyntacticRange(8, 14), result.occurrences[0].range)
        assertEquals(SyntacticRange(9, 14), result.occurrences[0].nameRange)
        assertEquals(":second", sql.substring(result.occurrences[1].range.start, result.occurrences[1].range.end))
        assertNull(result.failureOffset)
        assertFails { parseParameterOccurrences(sql, "doris") }
    }

    @Test
    fun matchesStrictModeOnOneStatement() {
        val sql = "SELECT :a + :b FROM t WHERE c = :a"
        assertEquals(parseParameterOccurrences(sql, "doris"), SqlScriptParameters.parse(sql, "doris").occurrences)
    }

    @Test
    fun stopsAtTheFirstLexicalFailure() {
        for (tail in listOf("'open; :hidden", "\"open; :hidden", "`open; :hidden", "/* open; :hidden",
            "/*+ open; :hidden", "$\$open; :hidden", "/* a /* b */ ; :hidden")) {
            val sql = "SELECT :before, $tail; SELECT :after"
            val result = SqlScriptParameters.parse(sql, "doris")
            assertEquals(listOf("before"), result.occurrences.map { it.name }, sql)
            assertEquals(sql.indexOf(tail), result.failureOffset, sql)
            assertNotNull(result.error)
        }
    }

    @Test
    fun hiddenTextIsNotScanned() {
        assertEquals(
            listOf("before", "after"),
            names("SELECT :before /* a /* ; :x */ :y */ -- :z\\\n:w\n, '|> :q', $\$:r$$, `:s`, :after"),
        )
        assertEquals(listOf("before", "after"), names("SELECT :before /*+ SET_VAR(a=1) /* :x */ :y */, :after"))
    }

    @Test
    fun mapEntryKeysAreSyntax() {
        assertEquals(listOf("p", "after"), names("SELECT MAP{'k':value, 'q': :p, 'r' : date}, :after"))
        assertEquals(listOf("v", "w"), names("SELECT MAP{'k': IF(flag, :v, 0), 'q':arr[1:n]}, {\"k\": :w}"))
        assertEquals(listOf("p"), names("SELECT {'a': :p, 'b':c}", "duckdb"))
    }

    @Test
    fun structFieldTypesAreSyntaxAtAnyDepth() {
        assertEquals(
            listOf("p", "after"),
            names("SELECT CAST(:p AS ARRAY<STRUCT<a:INT,b:MAP<STRING,INT>>>), CAST(x AS STRUCT<n : INT>), :after"),
        )
        assertEquals(listOf("q"), names("SELECT CAST(x AS STRUCT<a:INT; SELECT :q"))
    }

    @Test
    fun variantSubColumnTypesAreSyntax() {
        assertEquals(listOf("p"), names("SELECT CAST(v AS VARIANT<'a':INT, 'b':STRING>), :p"))
    }

    @Test
    fun slicesAfterCompletedOperandsAreSyntax() {
        assertEquals(listOf("p"), names("SELECT arr[CASE WHEN TRUE THEN 1 ELSE 2 END:n], arr[NULL:m], arr[TRUE:k], :p"))
        assertEquals(listOf("a"), names("SELECT arr[:a:b]"))
        assertEquals(listOf("date"), names("SELECT arr[:date:n]"))
    }

    @Test
    fun sliceColonsAreSyntax() {
        assertEquals(
            listOf("i", "j", "m", "lo", "s"),
            names("SELECT arr[1:2], arr[1:n], arr[:i], arr[1 + :j], arr[1 & :m], arr[IF(x BETWEEN :lo AND 9, 1, 2)], " +
                "arr[CASE :s WHEN 1 THEN 2 END], arr[f(x):n]"),
        )
        assertEquals(listOf("a", "b"), names("SELECT [:a, :b]"))
    }

    @Test
    fun onlyAdjacentAsciiNamesAreBinds() {
        assertEquals(
            listOf("p", "date", "limit", "after"),
            names("SELECT :1, :\u00e9, : name, ::name, value::type, :p::INT, :date, :limit, name LIKE:after, \$tag\$a; :x\$tag\$"),
        )
    }

    @Test
    fun fusedMultiwordKeywordsBindTheirFirstWord() {
        val sql = "SELECT :double precision, :Character\n  Varying, :p"
        val binds = SqlScriptParameters.parse(sql, "doris").occurrences
        assertEquals(listOf("double", "Character", "p"), binds.map { it.name })
        assertEquals(listOf(":double", ":Character", ":p"), binds.map { sql.substring(it.range.start, it.range.end) })
        assertEquals(listOf("double", "Character", "p"), binds.map { range -> range.nameRange!!.let { sql.substring(it.start, it.end) } })
    }

    @Test
    fun offsetsAreUtf16() {
        val sql = "SELECT '\ud83d\ude00', :first;\r\nSELECT `\ud83d\ude00;`, :second"
        val ranges = SqlScriptParameters.parse(sql, "doris").occurrences.map { sql.substring(it.range.start, it.range.end) }
        assertEquals(listOf(":first", ":second"), ranges)
    }

    @Test
    fun usesTheDialectLexer() {
        assertEquals(listOf("before", "hidden", "after"), names("SELECT :before # :hidden\n, :after"))
        assertEquals(listOf("before", "after"), names("SELECT :before # :hidden\n, :after", "mysql"))
        assertEquals(listOf("p"), names("SELECT $$ :x $$, :p", "duckdb"))
    }
}
