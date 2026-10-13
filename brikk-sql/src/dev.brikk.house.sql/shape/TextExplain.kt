package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Alter
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.ast.Commit
import dev.brikk.house.sql.ast.Copy
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.Delete
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Drop
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.FunctionSpecification
import dev.brikk.house.sql.ast.Insert
import dev.brikk.house.sql.ast.Into
import dev.brikk.house.sql.ast.Merge
import dev.brikk.house.sql.ast.Parameter
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.Rollback
import dev.brikk.house.sql.ast.Set as SetStatement
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Transaction
import dev.brikk.house.sql.ast.TruncateTable
import dev.brikk.house.sql.ast.Update
import dev.brikk.house.sql.ast.Use
import dev.brikk.house.sql.ast.UserDefinedFunction
import dev.brikk.house.sql.ast.Values
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType

/** Outcome of [prepareTextExplain]. */
sealed interface ExplainPreparation {
    /** Run [sql]; its rows are the plan ([explainPlanText]). */
    data class Ready(val sql: String) : ExplainPreparation

    /** Not explained, because of [reason]. */
    data class Refused(val reason: String) : ExplainPreparation
}

/** Which statements a dialect's text explain runs. Only queries so far; another set needs evidence its EXPLAIN writes nothing. */
sealed interface ExplainableStatements {
    /** A single plain query: the default, and all Trino's `EXPLAIN` is known not to change data for. */
    data object Queries : ExplainableStatements
}

/** Dialects whose `EXPLAIN` of a plain query only plans it: Trino (without ANALYZE) and Doris. */
val TEXT_EXPLAIN_DIALECTS: Set<String> = setOf("trino", "doris")

/**
 * brikk-native: the `EXPLAIN` that shows [statement]'s plan as text in [dialect], or why it must not
 * run. Only a single plain query is explained ([statements]), since `EXPLAIN` of other statements may
 * change data: Trino 483 creates the table of an `EXPLAIN CREATE TABLE ... AS SELECT`. Anywhere in the
 * query, refused are: statements (DML in a CTE, `SELECT ... INTO`), routine declarations (Trino's
 * `WITH FUNCTION`, which may take a built-in's name or run Python), table functions in relations
 * (Trino `TABLE(...)`, Doris table-valued functions), namespaced routine calls, and calls whose name as
 * written [isBuiltinFunction] does not know; grammar forms with their own syntax (`CAST`, `EXTRACT`,
 * `CASE`, ...) are not calls. Parameters (the dialect's, and the console's default `${name}` and
 * `#{name}`) are refused: they are resolved with Execute. A query the core
 * parser cannot read is refused, never guessed at. PIPE syntax must be lowered first. A query starting
 * with a parenthesis is explained as a derived table, since `EXPLAIN (` opens Trino's options. DuckDB is
 * refused outright: its `EXPLAIN` evaluates table function arguments, so
 * `EXPLAIN SELECT * FROM range(nextval('s'))` advances the sequence.
 */
fun prepareTextExplain(
    statement: String,
    dialect: String,
    statements: ExplainableStatements = ExplainableStatements.Queries,
    isBuiltinFunction: (String) -> Boolean,
): ExplainPreparation {
    if (dialect !in TEXT_EXPLAIN_DIALECTS) {
        return ExplainPreparation.Refused(
            if (dialect == "duckdb") "DuckDB evaluates table function arguments while it plans EXPLAIN, so EXPLAIN is not run for it."
            else "EXPLAIN is not run for $dialect: it is not known to only plan."
        )
    }
    val ranges = SqlScriptSnapshot.parse(statement, dialect).statements
    ranges.firstNotNullOfOrNull { it.boundaryError }?.let { return ExplainPreparation.Refused(it) }
    val query = ranges.filter { it.hasSql }.singleOrNull() ?: return ExplainPreparation.Refused("Select exactly one statement to explain.")
    if (query.hasPipe) return ExplainPreparation.Refused("PIPE syntax must be lowered before EXPLAIN.")
    val sql = statement.substring(query.start, query.terminator ?: query.end).trim()
    val parameters = try {
        parseParameterOccurrences(sql, dialect).isNotEmpty() || hasUserParameters(sql, dialect)
    } catch (_: ParseError) {
        return ExplainPreparation.Refused(UNREADABLE)
    } catch (_: TokenError) {
        return ExplainPreparation.Refused(UNREADABLE)
    }
    if (parameters) return ExplainPreparation.Refused(PARAMETERS)
    val ast = try {
        Dialects.forName(dialect).parseOne(sql)
    } catch (_: ParseError) {
        return ExplainPreparation.Refused(UNREADABLE)
    } catch (_: TokenError) {
        return ExplainPreparation.Refused(UNREADABLE)
    }
    when (statements) {
        ExplainableStatements.Queries -> if (ast !is Query && ast !is Values) {
            return ExplainPreparation.Refused("Only queries are explained: EXPLAIN of other statements may change data.")
        }
    }
    for (node in ast.findAll(Expression::class)) refusal(node, sql, dialect, isBuiltinFunction)?.let { return ExplainPreparation.Refused(it) }
    return ExplainPreparation.Ready(if (sql.startsWith("(")) "EXPLAIN SELECT * FROM ($sql) AS explained" else "EXPLAIN $sql")
}

/** Why [node] of the query [sql] must not be planned, or null. */
private fun refusal(node: Expression, sql: String, dialect: String, isBuiltinFunction: (String) -> Boolean): String? = when {
    node::class in STATEMENTS -> "The query holds a statement (${node.key.uppercase()}), which EXPLAIN may run."
    node is FunctionSpecification || node is UserDefinedFunction -> "The query declares a routine, which the engine may run while planning."
    node is Placeholder || node is Parameter -> PARAMETERS
    node is Table && node.args["this"] is Func -> "The query reads a table function, which the engine may run while planning: ${node.sql(dialect)}"
    node is Func && node.parent is Dot -> "The query calls a routine, which the engine may run while planning: ${node.sql(dialect)}"
    node is Func -> writtenName(node, sql)?.takeUnless(isBuiltinFunction)?.let { name ->
        "The query calls $name, which is not a built-in function the engine is known to plan safely."
    }
    else -> null
}

/**
 * The name [call] is written with in [sql], unquoted, from the name token the parser records; null for
 * grammar forms (`CAST`, `EXTRACT`, `CASE`, `ARRAY[...]`), which have their own syntax and no name to call.
 */
private fun writtenName(call: Expression, sql: String): String? {
    if (call is Anonymous) return call.name
    val start = call.meta["start"] as? Int ?: return null
    val end = call.meta["end"] as? Int ?: return null
    val written = sql.substring(start, end + 1)
    val quote = written.first()
    return if (quote in "\"`" && written.length > 1 && written.last() == quote) written.substring(1, written.length - 1).replace("$quote$quote", "$quote") else written
}

/** The console's default user parameters, `${name}` and `#{name}`, which the dialect reads as other tokens. */
private fun hasUserParameters(sql: String, dialect: String): Boolean = try {
    Dialects.forName(dialect).tokenize(sql).zipWithNext().any { (head, brace) ->
        brace.tokenType == TokenType.L_BRACE && (head.tokenType == TokenType.HASH || head.text == "$") && head.end + 1 == brace.start
    }
} catch (_: TokenError) {
    false
}

private val STATEMENTS = setOf(
    Insert::class, Update::class, Delete::class, Merge::class, Create::class, Drop::class, Alter::class, Command::class,
    Into::class, Copy::class, TruncateTable::class, Use::class, SetStatement::class, Transaction::class, Commit::class, Rollback::class,
)

private const val PARAMETERS = "The query has parameters: resolve them with Execute before EXPLAIN."

/** The plan text of `EXPLAIN` [rows], each row's first column: one line each for Doris, one block for Trino. */
fun explainPlanText(rows: List<String?>): String = rows.joinToString("\n") { it.orEmpty() }.trimEnd()

private const val UNREADABLE = "The statement could not be read as a plain query, so it is not explained."
