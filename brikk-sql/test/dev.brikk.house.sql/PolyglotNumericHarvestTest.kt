package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.ParseError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Adapted from Polyglot v0.14.0 numeric_exponent_boundaries.rs, MIT.
 * Copyright (c) 2026 TobiLG <github@tobilg.com>.
 * Pinned original and license: vendor/data/test-expansion/polyglot/; see ATTRIBUTIONS.md.
 * Expectations test token boundaries, not Polyglot's AST representation.
 */
class PolyglotNumericHarvestTest {
    private val numbers = listOf("13", "1.5", ".5", "1e3", "1E+3", "1e-3", ".5e2")

    @Test fun adjacentEndDoesNotBecomePartOfTheExponent() {
        for (prefix in listOf("", "/* π */")) for (number in numbers) {
            val sql = "$prefix${number}END"
            val tokens = Tokenizer().tokenize(sql)
            // A leading dot is its own token; parsePrimary joins DOT NUMBER back into 0.N as sqlglot does.
            val numberTokens = if (number.startsWith(".")) listOf(".", number.drop(1)) else listOf(number)
            val numberTypes = if (number.startsWith(".")) listOf(TokenType.DOT, TokenType.NUMBER) else listOf(TokenType.NUMBER)
            assertEquals(numberTypes + TokenType.END, tokens.map { it.tokenType }, sql)
            assertEquals(numberTokens + "END", tokens.map { it.text }, sql)
        }
    }

    @Test fun compactCaseResultMatchesSpacedResultThroughCopyAndSerde() {
        for (number in numbers) {
            val sql = "SELECT CASE WHEN TRUE THEN ${number}END AS value"
            val compact = Dialects.DUCKDB.parseOne(sql)
            val spaced = Dialects.DUCKDB.parseOne(sql.replace("END", " END"))
            assertEquals(spaced, compact, sql)
            for (tree in listOf(compact, compact.copy(), Serde.loadExpression(Serde.dump(compact)))) {
                assertEquals(spaced.sql("duckdb"), tree.sql("duckdb"), sql)
            }
        }
    }

    @Test fun completeScientificNotationParsesAsTheExpectedNumericValue() {
        for (number in numbers.filter { 'e' in it.lowercase() } + "1.e2") {
            // Brikk may split a leading dot into a separate token. Numeric AST meaning,
            // not identical token count to Polyglot, is the public contract here.
            val value = assertIs<Literal>(Dialects.DUCKDB.parseOne("SELECT $number").expressionsArg.single())
            assertFalse(value.isString, number)
            assertEquals(number.toDouble(), value.thisArg.toString().toDouble(), number)
        }
    }

    @Test fun incompleteExponentsDoNotBecomeSuccessfulNumberAndAliasPairs() {
        for (sql in listOf("1e", "1E+", ".5e", ".5E-")) {
            // Native DuckDB 2.0 tokenizes these as numbers and fails DOUBLE conversion
            // later, not during parsing. Polyglot's NUMBER + identifier token policy
            // must not become a requirement to accept SELECT 1 AS e successfully.
            val tree = try {
                Dialects.DUCKDB.parseOne("SELECT $sql")
            } catch (_: ParseError) {
                // Earlier rejection of an invalid numeric value is also safe. Only
                // silently converting it to a successful number/alias pair is wrong.
                continue
            }
            val value = assertIs<Literal>(tree.expressionsArg.single(), sql)
            assertEquals(sql.lowercase(), value.name.lowercase().removePrefix("0"), sql)
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)), sql)
            assertEquals(tree, Dialects.DUCKDB.parseOne(tree.sql("duckdb")), sql)
        }
    }
}
