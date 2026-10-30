package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Show

/** SHOW name is resolved at execution using show_behavior and the live catalog. */
internal fun Expression.isDuckdbNameShow(): Boolean = this is Show && name in setOf("DUCKDB_NAME", "DUCKDB_QUERY")
