package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.metadata.DATAFUSION_FUNCTION_CATALOG
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatafusionFunctionAuditTest {
    @Test
    fun everyRegisteredNameKeepsEveryAcceptedArgumentInOrder() {
        val names = DATAFUSION_FUNCTION_CATALOG.functions.flatMap { listOf(it.name) + it.aliases }.toSet()
        val marker = Regex("__df_arg_\\d+", RegexOption.IGNORE_CASE)
        assertEquals(315, names.size)
        var accepted = 0
        for (name in names) for (arity in 0..6) {
            val arguments = (0 until arity).map { "__df_arg_$it" }
            val sql = "SELECT $name(${arguments.joinToString(", ")}) AS v"
            val ast = try { parseOne(sql, "datafusion") } catch (_: ParseError) { continue }
            val rendered = ast.sql("datafusion")
            assertEquals(arguments, marker.findAll(rendered).map { it.value.lowercase() }.toList(), sql)
            accepted++
        }
        assertTrue(accepted > 2000)
    }

    @Test
    fun nativeAliasesUnknownCallsAndQuotedNamesDoNotAcquireForeignSemantics() {
        for (call in listOf("isnan(x)", "to_hex(x)", "signum(x)", "position(haystack, needle)",
            "date_add(x, y)", "vector_search(t, 'q')", "square(x)", "public.square(x)", "\"SUM\"(x)")) {
            assertEquals("SELECT $call", parseOne("SELECT $call", "datafusion").sql("datafusion"))
        }
        assertTrue(parseOne("SELECT var_pop(x), first_value(x), string_agg(x, ',') FROM t", "datafusion")
            .walk().filterIsInstance<AggFunc>().count() == 3)
        assertTrue(parseOne("SELECT \"sum\"(x) FROM t", "datafusion").walk().any { it is AnonymousAggFunc })
        assertEquals("SELECT \"sum\"(x) FROM t", parseOne("SELECT \"sum\"(x) FROM t", "datafusion").sql("datafusion"))
        val comments = parseOne("SELECT abs /* function-comment */ (x /* argument-comment */)", "datafusion").sql("datafusion")
        assertEquals(1, Regex("function-comment").findAll(comments).count())
        assertEquals(1, Regex("argument-comment").findAll(comments).count())
    }

    @Test
    fun invalidArgumentListsAreNotSilentlyRepaired() {
        for (sql in listOf("SELECT array_agg(x, ORDER BY x)", "SELECT sum(x,)", "SELECT concat(x,,y)")) {
            assertFailsWith<ParseError>(sql) { parseOne(sql, "datafusion") }
        }
        assertEquals("SELECT abs(x, y)", parseOne("SELECT abs(x, y)", "datafusion").sql("datafusion"))
        assertEquals("SELECT count(DISTINCT x, y)", parseOne("SELECT count(DISTINCT x, y)", "datafusion").sql("datafusion"))
    }

    @Test
    fun oneBasedArrayIndexesUseCanonicalOffsetsAcrossDialects() {
        val result = transpile("SELECT ARRAY[10, 20][1]", "datafusion", "duckdb")
        assertTrue(result.endsWith("[1]"), result)
        assertEquals("SELECT ARRAY[10, 20][1]", parseOne("SELECT ARRAY[10, 20][1]", "datafusion").sql("datafusion"))
    }

    @Test
    fun encodedStringsMatchNativeNumericAndInvalidEscapeRules() {
        for (escape in listOf("\\x41", "\\u0041", "\\101")) {
            val node = parseOne("SELECT E'$escape'", "datafusion").find(ByteString::class) as ByteString
            assertEquals("A", node.thisArg)
        }
        assertEquals("SELECT E'q'", parseOne("SELECT E'\\q'", "datafusion").sql("datafusion"))
        for (escape in listOf("\\uD800", "\\uD83D\\uDE00", "\\u12", "\\777", "\\0", "\\xC3\\xA9")) {
            assertFailsWith<TokenError>(escape) { parseOne("SELECT E'$escape'", "datafusion") }
        }
        assertTrue(Dialects.DATAFUSION.tokenizerConfig.withKeywords(emptyMap()).strictEncodedStrings)
    }
}
