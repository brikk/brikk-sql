package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adapted from Polyglot v0.14.0 quoted_relation_columns.rs, MIT.
 * Copyright (c) 2026 TobiLG <github@tobilg.com>.
 * Pinned original and license: vendor/data/test-expansion/polyglot/; see ATTRIBUTIONS.md.
 * Uses Brikk serde and copy contracts, not Polyglot's JSON schema.
 */
class PolyglotQuotedNamesHarvestTest {
    private fun preserve(sql: String, dialect: String, fragment: String) {
        val tree = Dialects.forName(dialect).parseOne(sql)
        val output = tree.sql(dialect)
        assertTrue(fragment in output, "$dialect: $sql -> $output")
        for (copy in listOf(tree.copy(), Serde.loadExpression(Serde.dump(tree)))) {
            assertEquals(output, copy.sql(dialect), sql)
        }
        assertEquals(output, Dialects.forName(dialect).parseOne(output).sql(dialect), sql)
    }

    @Test fun quotedRelationColumnsSurviveEveryRelationForm() {
        for (dialect in listOf("duckdb", "postgres")) for (sql in listOf(
            "SELECT * FROM (SELECT 1 AS x) AS d(\"a b\")",
            "SELECT * FROM (SELECT 1 AS x) d(\"a b\")",
            "SELECT * FROM records AS d(\"a b\")",
            "SELECT * FROM records d(\"a b\")",
            "WITH d(\"a b\") AS (SELECT 1 AS x) SELECT * FROM d",
            "SELECT * FROM (VALUES (1)) AS d(\"a b\")",
            "SELECT * FROM UNNEST(ARRAY[1]) AS d(\"a b\")",
            "SELECT * FROM LATERAL UNNEST(ARRAY[1]) AS d(\"a b\")",
        )) preserve(sql, dialect, "\"a b\"")
    }

    @Test fun quotedAliasTextDoesNotAcquireIndexOrOrderingMeaning() {
        for (dialect in listOf("duckdb", "postgres")) for (alias in listOf(
            "AS items(\"value(1)\")", "AS items(\"value ASC\")", "AS items(\"value DESC\")",
            "AS \"items(1)\"(value)", "AS \"items ASC\"(value)", "AS \"items DESC\"(\"value(1)\")",
        )) for (relation in listOf("LATERAL UNNEST(ARRAY[1])", "(VALUES (1))", "(SELECT 1)")) {
            preserve("SELECT * FROM $relation $alias", dialect, alias)
        }
    }

    @Test fun escapedAndMixedLateralAliasesSurviveCopyAndSerde() {
        for (dialect in listOf("duckdb", "postgres")) for (alias in listOf(
            "AS \"item rows\"(\"unit value\")", "AS \"Items\"(\"Value\")",
            "AS d(\"a\"\"b\")", "AS d(unquoted, \"unit value\")",
        )) preserve("SELECT * FROM LATERAL UNNEST(ARRAY[1]) $alias", dialect, alias)
    }

    @Test fun ordinalityIsNotDuplicatedDuringSerialization() {
        for (dialect in listOf("duckdb", "postgres")) for (function in listOf(
            "UNNEST(ARRAY[1, 2])", "GENERATE_SERIES(1, 2)",
        )) {
            val sql = "SELECT \"item rows\".\"row number\" FROM LATERAL $function WITH ORDINALITY AS \"item rows\"(\"unit value\", \"row number\")"
            preserve(sql, dialect, "\"item rows\"(\"unit value\", \"row number\")")
            val tree = Dialects.forName(dialect).parseOne(sql)
            val output = Serde.loadExpression(Serde.dump(tree)).sql(dialect)
            assertEquals(1, Regex("WITH ORDINALITY").findAll(output).count(), output)
        }
    }

    @Test fun quotedConstraintNamesKeepLiteralSuffixesSeparateFromModifiers() {
        for (dialect in listOf("mysql", "duckdb", "postgres")) {
            val quote = if (dialect == "mysql") '`' else '"'
            for (name in listOf("name(1)", "name ASC", "name DESC", "a`b(1)", "a\"b DESC")) {
                val identifier = "$quote${name.replace(quote.toString(), "$quote$quote")}$quote"
                for (key in listOf("PRIMARY KEY", "UNIQUE")) {
                    for (suffix in if (dialect == "mysql") listOf("", " ASC", " DESC", "(16)", "(16) DESC") else listOf("")) {
                        val sql = "CREATE TABLE t ($identifier VARCHAR(64), $key ($identifier$suffix))"
                        preserve(sql, dialect, "$key ($identifier$suffix)")
                    }
                }
            }
        }
    }
}
