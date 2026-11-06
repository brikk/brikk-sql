package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.ParseError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Adapted from Polyglot v0.14.0 deep_nesting_regression.rs and unicode_token_ranges.rs, MIT.
 * Copyright (c) 2026 TobiLG <github@tobilg.com>.
 * Originals and license: vendor/data/test-expansion/polyglot/; see ATTRIBUTIONS.md.
 * Depths use Brikk's supported budget, and positions use UTF-16, not Rust character offsets.
 */
class PolyglotRobustnessHarvestTest {
    @Test fun unicodeAndEscapedTokensKeepUtf16SlicesAndMakeProgress() {
        for (sql in listOf(
            "SELECT café, δelta, 'été\n😀', \"naïve\" FROM données",
            "SELECT abcδ, δabc, 中文 FROM t",
            "SELECT 'it''s été', '😀', \"naï\"\"ve\" FROM t",
            "SELECT ${'$'}${'$'}été\n😀${'$'}${'$'}, ${'$'}tag${'$'}δ${'$'}tag${'$'}, 1.25e-2 /* π */",
        )) {
            val tokens = Dialects.POSTGRES.tokenize(sql)
            assertTrue(tokens.isNotEmpty(), sql)
            for ((index, token) in tokens.withIndex()) {
                assertTrue(token.start in sql.indices && token.end in token.start until sql.length, "$sql: $token")
                if (index > 0) assertTrue(token.start > tokens[index - 1].end, "$sql: $token")
            }
            // Locations after the supplementary character must still slice the exact authored name.
            tokens.firstOrNull { it.text == "naïve" }?.let {
                assertEquals("\"naïve\"", sql.substring(it.start, it.end + 1))
            }
            val tree = Dialects.POSTGRES.parseOne(sql)
            assertEquals(tree.sql("postgres"), tree.copy().sql("postgres"), sql)
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)), sql)
        }
    }

    @Test fun reasonableNestedFunctionsSurviveParseCopyTransformAndGeneration() {
        for (dialect in listOf("postgres", "clickhouse")) for (depth in listOf(8, 24, 48)) {
            val sql = "SELECT " + "COALESCE(".repeat(depth) + "value" + ", 0)".repeat(depth) + " FROM records"
            val tree = Dialects.forName(dialect).parseOne(sql)
            val changed = tree.transform { node ->
                if (node is Identifier && node.name == "value") Identifier(args("this" to "renamed", "quoted" to false)) else node
            }
            val rendered = changed.sql(dialect)
            assertTrue("renamed" in rendered, rendered)
            assertEquals(depth, Regex("COALESCE\\(").findAll(rendered.uppercase()).count(), rendered)
            assertEquals(tree.sql(dialect), tree.copy().sql(dialect))
            assertEquals(rendered, Dialects.forName(dialect).parseOne(rendered).sql(dialect))
            assertEquals(changed, Serde.loadExpression(Serde.dump(changed)))
        }
    }

    @Test fun excessiveParenthesisNestingReportsAnOrdinaryParseError() {
        val sql = "SELECT " + "COALESCE(".repeat(1024) + "1" + ", 0)".repeat(1024)
        for (dialect in listOf("postgres", "clickhouse")) {
            assertFailsWith<ParseError>(dialect) { Dialects.forName(dialect).parseOne(sql) }
            assertEquals("SELECT 1", Dialects.forName(dialect).parseOne("SELECT 1").sql(dialect))
        }
    }
}
