package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Cases proven on the pinned DataFusion 55.2.0 reference, not documentation assumptions. */
class DatafusionNativeRegressionTest {
    private fun roundTrip(sql: kotlin.String) = parseOne(sql, "datafusion").sql("datafusion")

    @Test
    fun regexFlagsAndDateFormatsSurviveGeneration() {
        assertEquals("SELECT regexp_like('ABC', 'abc', 'i')", roundTrip("SELECT regexp_like('ABC', 'abc', 'i')"))
        assertEquals("SELECT to_char(d, '%d-%m-%Y') FROM t", roundTrip("SELECT to_char(d, '%d-%m-%Y') FROM t"))
        assertEquals("SELECT s ~ '^a' FROM t", roundTrip("SELECT s ~ '^a' FROM t"))
    }

    @Test
    fun variadicMapsAndNativeSortOptionsDoNotLoseArguments() {
        assertEquals("SELECT map('a', 1, 'b', 2)", roundTrip("SELECT map('a', 1, 'b', 2)"))
        assertEquals("SELECT array_sort(ARRAY[3, NULL, 1], 'DESC', 'NULLS LAST')", roundTrip("SELECT array_sort([3, NULL, 1], 'DESC', 'NULLS LAST')"))
    }

    @Test
    fun dateBinOriginIsAnExpressionRatherThanAUnit() {
        val ast = parseOne("SELECT date_bin(INTERVAL '1 day', ts, '2023-01-01T03:00:00') FROM t", "datafusion")
        val bin = ast.find(DateBin::class) as DateBin
        assertTrue(bin.args["origin"] is Literal)
        assertEquals(null, bin.args["unit"])
        assertTrue(ast.sql("datafusion").contains(", '2023-01-01T03:00:00')"))
    }

    @Test
    fun stringAggOrderingFollowsDelimiterAndRenderingDoesNotMutate() {
        for (value in listOf("name", "DISTINCT name")) {
            val source = "SELECT string_agg($value, ', ' ORDER BY name DESC) FROM t"
            val ast = parseOne(source, "datafusion")
            val before = ast.toString()
            assertEquals(source, Dialects.DATAFUSION.generate(ast, copy = false))
            assertEquals(before, ast.toString())
        }
        val nested = parseOne("SELECT string_agg((SELECT name FROM t ORDER BY name DESC LIMIT 1), ',') FROM u", "datafusion")
        val before = nested.toString()
        val rendered = nested.sql("datafusion")
        val reparsed = parseOne(rendered, "datafusion")
        assertTrue((reparsed.find(Subquery::class) as Subquery).find(Order::class) is Order)
        assertEquals(before, nested.toString())
    }

    @Test
    fun defaultNullOrderingMatchesNativeAscendingAndDescending() {
        val ast = parseOne("SELECT x FROM t ORDER BY x ASC, x DESC", "datafusion")
        val orders = ast.walk().filterIsInstance<Ordered>().toList()
        assertFalse(orders[0].args["nulls_first"] as kotlin.Boolean)
        assertTrue(orders[1].args["nulls_first"] as kotlin.Boolean)
        assertTrue(roundTrip("SELECT x FROM t ORDER BY x DESC NULLS LAST").endsWith("DESC NULLS LAST"))
    }

    @Test
    fun dollarStringsRemainOneStatementAndParametersAreNotColumns() {
        val source = "SELECT \$tag\$a;b\$tag\$, \$1"
        val statements = Dialects.DATAFUSION.parse(source).filterNotNull()
        assertEquals(1, statements.size)
        assertTrue(statements.single().walk().any { it is Parameter })
        assertFalse(statements.single().walk().filterIsInstance<Column>().any { it.name == "\$1" })
        assertEquals("SELECT 'a;b', \$1", statements.single().sql("datafusion"))
    }

    @Test
    fun arraysAndEscapedAndHexLiteralsRetainTheirNativeForms() {
        assertEquals("SELECT ARRAY[1, 2] @> ARRAY[1]", roundTrip("SELECT [1, 2] @> [1]"))
        assertEquals("SELECT ARRAY[1] <@ ARRAY[1, 2]", roundTrip("SELECT [1] <@ [1, 2]"))
        assertEquals("SELECT X'0F'", roundTrip("SELECT x'0F'"))
        val rendered = roundTrip("SELECT E'foo\\nbar'")
        assertTrue(rendered.contains("foo"))
        val literal = parseOne(rendered, "datafusion").find(ByteString::class) as ByteString
        assertEquals("foo\nbar", literal.thisArg)
        val backslash = parseOne("SELECT E'foo\\\\nbar'", "datafusion")
        val reparsed = parseOne(backslash.sql("datafusion"), "datafusion")
        assertEquals((backslash.find(ByteString::class) as ByteString).thisArg,
            (reparsed.find(ByteString::class) as ByteString).thisArg)
        assertEquals(backslash.sql("datafusion"), dev.brikk.house.sql.dialects.DatafusionGenerator().generate(backslash))
    }

    @Test
    fun malformedTailCannotBecomeAPartiallyAcceptedScript() {
        assertFailsWith<ParseError> { Dialects.DATAFUSION.parse("SELECT 1; SELECT (") }
        assertFailsWith<TokenError> { Dialects.DATAFUSION.parse("SELECT 'unterminated") }
        assertFailsWith<TokenError> { Dialects.DATAFUSION.parse("SELECT 1; /* unterminated") }
    }
}
