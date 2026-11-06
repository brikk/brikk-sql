package dev.brikk.house.sql

import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SQL and dependency assertions adapted from Polyglot v0.14.0 query_analysis_regression.rs, MIT.
 * Copyright (c) 2026 TobiLG <github@tobilg.com>.
 * Pinned original and license: vendor/data/test-expansion/polyglot/; see ATTRIBUTIONS.md.
 * Exercises Brikk's public physical-input API rather than copying Polyglot's scope layout.
 */
class PolyglotJoinScopeHarvestTest {
    @Test fun physicalInputsIncludeNestedJoinMembersAndPredicateSubqueries() {
        for ((sql, names) in listOf(
            "SELECT a.id FROM alpha a JOIN (beta b JOIN gamma c ON b.id = c.id) ON a.id = b.id" to listOf("alpha", "beta", "gamma"),
            "SELECT a.id FROM alpha a JOIN ((beta b JOIN gamma c ON b.id = c.id) LEFT JOIN delta d ON c.id = d.id) ON a.id = b.id" to listOf("alpha", "beta", "delta", "gamma"),
            "WITH chosen AS (SELECT id FROM gamma) SELECT a.id FROM alpha a JOIN (beta b JOIN chosen c ON b.id = c.id) ON a.id = b.id" to listOf("alpha", "beta", "gamma"),
            "SELECT a.id FROM alpha a JOIN (beta b JOIN gamma c ON b.id = c.id) AS grouped ON a.id = grouped.id" to listOf("alpha", "beta", "gamma"),
            "SELECT a.id FROM alpha a JOIN (beta b JOIN gamma c ON b.id = c.id) AS grouped(x, y) ON a.id = grouped.x" to listOf("alpha", "beta", "gamma"),
            "SELECT a.id FROM alpha a JOIN (beta b JOIN gamma c ON c.id IN (SELECT id FROM delta)) ON a.id = b.id" to listOf("alpha", "beta", "delta", "gamma"),
            "SELECT a.id FROM alpha a JOIN (beta b JOIN gamma c ON c.id IN (SELECT id FROM delta)) AS grouped(x, y) ON a.id = grouped.x" to listOf("alpha", "beta", "delta", "gamma"),
        )) assertEquals(names, SqlFragment(sql, "duckdb").sourceTables.sorted(), sql)
    }

    @Test fun repeatedJoinWrappingAndRealiasingDoNotHidePhysicalSources() {
        for (group in listOf(
            "((beta b JOIN gamma c ON b.id = c.id) AS inner_g(x, y))",
            "((((beta b JOIN gamma c ON b.id = c.id) AS inner_g(x, y))))",
        )) for (alias in listOf("outer_g", "outer_g(u, v)")) {
            val sql = "SELECT 1 FROM $group AS $alias"
            assertEquals(listOf("beta", "gamma"), SqlFragment(sql, "duckdb").sourceTables.sorted(), sql)
        }
    }
}
