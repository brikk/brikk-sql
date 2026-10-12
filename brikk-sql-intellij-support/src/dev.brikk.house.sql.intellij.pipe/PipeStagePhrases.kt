package dev.brikk.house.sql.intellij.pipe

import dev.brikk.house.sql.parser.pipeStageKeywords
import java.util.Locale
import org.jetbrains.annotations.ApiStatus

/**
 * The stage phrases core parses for [dialect] (`pipeStageKeywords`) that core lowers for it and
 * the engine runs as lowered, in core's order. A dialect without recorded evidence gets every
 * phrase core parses.
 */
@ApiStatus.Experimental
public fun pipeStagePhrases(dialect: String): List<String> {
    val parsed = pipeStageKeywords(dialect)
    val runnable = RUNNABLE[dialect.lowercase(Locale.ROOT)] ?: return parsed
    return parsed.filter { it in runnable }
}

private val SHARED = setOf(
    "AGGREGATE", "AS", "DISTINCT", "EXTEND", "LIMIT", "OFFSET", "ORDER BY", "SELECT", "WHERE", "WINDOW",
    "JOIN", "INNER JOIN", "CROSS JOIN", "LEFT JOIN", "LEFT OUTER JOIN", "RIGHT JOIN", "RIGHT OUTER JOIN",
    "FULL JOIN", "FULL OUTER JOIN", "SEMI JOIN", "ANTI JOIN",
    "UNION ALL", "UNION DISTINCT", "INTERSECT", "INTERSECT ALL", "INTERSECT DISTINCT", "EXCEPT", "EXCEPT ALL", "EXCEPT DISTINCT",
)

// Each phrase was lowered by core and its output run on the engine: Trino 483, DuckDB 1.5.6 and
// ClickHouse 26.7 executed it; for Doris the 4.1.4 FE parser accepted it. Left out, by evidence:
// STRAIGHT_JOIN everywhere (no engine parses `a STRAIGHT_JOIN b`); Trino ASOF JOIN (read as an
// alias) and NATURAL JOIN ("not supported"); Trino CALL (table functions need `TABLE(...)`), DROP
// and SET (no `* EXCEPT` / `* REPLACE`) and RENAME, PIVOT, UNPIVOT (core refuses or blocks them);
// Doris CALL and NATURAL JOIN (grammar), RENAME, PIVOT, UNPIVOT and TABLESAMPLE (core); ClickHouse
// CALL and RENAME (the lowered form does not run), NATURAL JOIN, PIVOT and UNPIVOT (core).
private val RUNNABLE: Map<String, Set<String>> = mapOf(
    "trino" to SHARED + setOf("TABLESAMPLE", "UNION"),
    "duckdb" to SHARED + setOf(
        "CALL", "DROP", "SET", "RENAME", "PIVOT", "UNPIVOT", "TABLESAMPLE", "UNION",
        "ASOF JOIN", "NATURAL JOIN", "POSITIONAL JOIN",
    ),
    "doris" to SHARED + setOf("DROP", "SET", "UNION", "ASOF JOIN"),
    "clickhouse" to SHARED + setOf("DROP", "SET", "TABLESAMPLE", "ALL JOIN", "ANY JOIN", "ASOF JOIN", "ARRAY JOIN"),
)
