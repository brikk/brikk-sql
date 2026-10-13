package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Values
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenError

/** Outcome of [prepareTextExplain]. */
sealed interface ExplainPreparation {
    /** Run [sql]; its rows are the plan ([explainPlanText]). */
    data class Ready(val sql: String) : ExplainPreparation

    /** Not explained, because of [reason]. */
    data class Refused(val reason: String) : ExplainPreparation
}

/** Dialects whose `EXPLAIN` of a plain query only plans it: Trino (without ANALYZE) and Doris. */
val TEXT_EXPLAIN_DIALECTS: Set<String> = setOf("trino", "doris")

/**
 * brikk-native: the `EXPLAIN` that shows [statement]'s plan as text in [dialect], or why it must not
 * run. Only a single plain query is explained, since `EXPLAIN` of other statements may change data:
 * Trino 483 creates the table of an `EXPLAIN CREATE TABLE ... AS SELECT`. A query is refused when
 * planning it could run code beyond the engine's built-ins: a table function in its relations
 * (Trino `TABLE(...)`, Doris table-valued functions), a namespaced function (a SQL routine), or a
 * function [isBuiltinFunction] does not know. A query the core parser cannot read is refused, never
 * guessed at. PIPE syntax must be lowered first. DuckDB is refused outright: its `EXPLAIN` evaluates
 * table function arguments, so `EXPLAIN SELECT * FROM range(nextval('s'))` advances the sequence.
 */
fun prepareTextExplain(statement: String, dialect: String, isBuiltinFunction: (String) -> Boolean): ExplainPreparation {
    if (dialect !in TEXT_EXPLAIN_DIALECTS) {
        return ExplainPreparation.Refused(
            if (dialect == "duckdb") "DuckDB evaluates table function arguments while it plans EXPLAIN, so EXPLAIN is not run for it."
            else "EXPLAIN is not run for $dialect: it is not known to only plan."
        )
    }
    val statements = SqlScriptSnapshot.parse(statement, dialect).statements
    statements.firstNotNullOfOrNull { it.boundaryError }?.let { return ExplainPreparation.Refused(it) }
    val query = statements.singleOrNull { it.hasSql }
        ?.takeIf { statements.count { range -> range.hasSql } == 1 }
        ?: return ExplainPreparation.Refused("Select exactly one statement to explain.")
    if (query.hasPipe) return ExplainPreparation.Refused("PIPE syntax must be lowered before EXPLAIN.")
    val sql = statement.substring(query.start, query.terminator ?: query.end).trim()
    val ast = try {
        Dialects.forName(dialect).parseOne(sql)
    } catch (_: ParseError) {
        return ExplainPreparation.Refused(UNREADABLE)
    } catch (_: TokenError) {
        return ExplainPreparation.Refused(UNREADABLE)
    }
    if (ast !is Query && ast !is Values) {
        return ExplainPreparation.Refused("Only queries are explained: EXPLAIN of other statements may change data.")
    }
    ast.findAll(Table::class).firstOrNull { it.args["this"] is Func }?.let { table ->
        return ExplainPreparation.Refused("The query reads a table function, which the engine may run while planning: ${table.sql(dialect)}")
    }
    for (call in ast.findAll(Anonymous::class)) {
        if (call.parent is Dot) return ExplainPreparation.Refused("The query calls a routine, which the engine may run while planning: ${call.name}")
        if (!isBuiltinFunction(call.name)) return ExplainPreparation.Refused("The query calls ${call.name}, which is not a built-in function the engine is known to plan safely.")
    }
    return ExplainPreparation.Ready("EXPLAIN $sql")
}

/** The plan text of `EXPLAIN` [rows], each row's first column: one line each for Doris, one block for Trino. */
fun explainPlanText(rows: List<String?>): String = rows.joinToString("\n") { it.orEmpty() }.trimEnd()

private const val UNREADABLE = "The statement could not be read as a plain query, so it is not explained."
