package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Alter
import dev.brikk.house.sql.ast.And
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Case
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.CurrentDate
import dev.brikk.house.sql.ast.CurrentTimestamp
import dev.brikk.house.sql.ast.Extract
import dev.brikk.house.sql.ast.If
import dev.brikk.house.sql.ast.JSONArrayContains
import dev.brikk.house.sql.ast.JSONExtract
import dev.brikk.house.sql.ast.JSONExtractScalar
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.ast.Or
import dev.brikk.house.sql.ast.ParseJSON
import dev.brikk.house.sql.ast.Soundex
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.VarMap
import dev.brikk.house.sql.ast.Xor
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
import dev.brikk.house.sql.ast.Hint
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
    /**
     * Run [context], then [sql], on a connection of its own; its rows are the plan ([explainPlanText]).
     * [context] makes only built-in functions resolve: a built-in's name alone does not prove the built-in
     * binds (Trino falls through to routines on the session path, Doris may prefer UDFs).
     */
    data class Ready(val sql: String, val context: List<String>) : ExplainPreparation

    /** Not explained, because of [reason]. */
    data class Refused(val reason: String) : ExplainPreparation
}

/** Which statements a dialect's text explain runs. Only queries so far; another set needs evidence its EXPLAIN writes nothing. */
sealed interface ExplainableStatements {
    /** A single plain query: the default, and all Trino's `EXPLAIN` is known not to change data for. */
    data object Queries : ExplainableStatements
}

/**
 * The session statements under which only built-in functions resolve: Trino's path holds only
 * `system.builtin` (a live Trino 483 then resolves no routine, also under EXPLAIN), and Doris prefers
 * built-ins over UDFs (`prefer_udf_over_builtin`, not verified on a live Doris).
 */
private val BUILTIN_ONLY: Map<String, List<String>> = mapOf(
    "trino" to listOf("SET PATH system.builtin"),
    "doris" to listOf("SET prefer_udf_over_builtin = false"),
)

/** Dialects whose `EXPLAIN` of a plain query only plans it, in a session where only built-ins resolve: Trino (without ANALYZE) and Doris. */
val TEXT_EXPLAIN_DIALECTS: Set<String> = BUILTIN_ONLY.keys

/**
 * brikk-native: the `EXPLAIN` that shows [statement]'s plan as text in [dialect], or why it must not
 * run. Only a single plain query is explained ([statements]), since `EXPLAIN` of other statements may
 * change data: Trino 483 creates the table of an `EXPLAIN CREATE TABLE ... AS SELECT`. Anywhere in the
 * query, refused are: statements (DML in a CTE, `SELECT ... INTO`), routine declarations (Trino's
 * `WITH FUNCTION`, which may take a built-in's name or run Python), table functions in relations
 * (Trino `TABLE(...)`, Doris table-valued functions), namespaced routine calls, and calls whose name as
 * written (the name token the parser recorded) [isBuiltinFunction] does not know or that recorded no name.
 * Operators, literals and keyword forms (`a AND b`, `TIMESTAMP '...'`, `CASE`, `CAST`, `ARRAY[...]`), and
 * nodes a parser synthesizes inside a named call, are not calls of their own. Parameters (the dialect's, and the console's default `${name}` and
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
    if (hasHint(sql, dialect)) return ExplainPreparation.Refused(HINT)
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
    // Decided on the first token, so leading comments do not hide the parenthesis; a trailing line comment ends before the wrapper closes.
    val parenthesized = Dialects.forName(dialect).tokenize(sql).firstOrNull()?.tokenType == TokenType.L_PAREN
    val explain = if (parenthesized) "EXPLAIN SELECT * FROM ($sql\n) AS explained" else "EXPLAIN $sql"
    return ExplainPreparation.Ready(explain, BUILTIN_ONLY.getValue(dialect))
}

/** Why [node] of the query [sql] must not be planned, or null. */
private fun refusal(node: Expression, sql: String, dialect: String, isBuiltinFunction: (String) -> Boolean): String? = when {
    node::class in STATEMENTS -> "The query holds a statement (${node.key.uppercase()}), which EXPLAIN may run."
    node is Hint -> HINT
    node is FunctionSpecification || node is UserDefinedFunction -> "The query declares a routine, which the engine may run while planning."
    node is Placeholder || node is Parameter -> PARAMETERS
    node is Table && node.args["this"] is Func -> "The query reads a table function, which the engine may run while planning: ${node.sql(dialect)}"
    node is Func && node.parent is Dot -> "The query calls a routine, which the engine may run while planning: ${node.sql(dialect)}"
    // Whatever node a call became, its name is checked: Doris PARSE_JSON(...) parses as JSON '...' does, yet calls a UDF.
    else -> when (val name = writtenName(node, sql)) {
        null -> if (node !is Func || isGrammarForm(node) || isSynthesized(node)) null else "The query calls a function whose name is not known: ${node.sql(dialect)}"
        else -> if (name.uppercase() in KEYWORD_SYNTAX || isBuiltinFunction(name)) null else "The query calls $name, which is not a built-in function the engine is known to plan safely."
    }
}

/** Names of keyword syntax the parser records like a call's, but which no function provides (`CASE ... END`, `CAST(x AS t)`, `ARRAY[...]`). */
private val KEYWORD_SYNTAX = setOf(
    "CASE", "CAST", "TRY_CAST", "EXTRACT", "ARRAY", "ROW", "INTERVAL", "DATE", "TIME", "TIMESTAMP",
    "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP",
)

/** Nodes without a recorded name that are no function a user could define: operators and literals (`a AND b`, `TIMESTAMP '...'`, `JSON '...'`). */
private fun isGrammarForm(node: Expression): Boolean = node is Cast || node::class in GRAMMAR_FORMS

/** A node a parser built for a named call, as Doris `TO_DAYS(x)` becomes `(DateDiff(TsOrDsToDate(x), ...) + 1)`: part of that call. */
private fun isSynthesized(node: Expression): Boolean =
    node !is Anonymous && generateSequence(node.parent) { it.parent }.any { it.callName != null }

/** The name [call] is written with in [sql], unquoted, from the name token the parser recorded; null when it recorded none. */
private fun writtenName(call: Expression, sql: String): String? {
    val range = call.callName ?: return (call as? Anonymous)?.name
    val written = sql.substring(range.first, range.last + 1)
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

private val GRAMMAR_FORMS = setOf(
    And::class, Or::class, Xor::class, Case::class, If::class, ArrayNode::class, MapNode::class, VarMap::class, Struct::class,
    Unnest::class, ParseJSON::class, Extract::class, CurrentDate::class, CurrentTimestamp::class,
    JSONArrayContains::class, JSONExtract::class, JSONExtractScalar::class, Soundex::class,
)

/**
 * Whether [sql] holds an optimizer hint or executable comment (`/` `*+`, `/` `*!`) anywhere outside literals:
 * Doris collects hints throughout a SELECT, and `SET_VAR` sets session variables for it.
 */
private fun hasHint(sql: String, dialect: String): Boolean {
    val tokens = try {
        Dialects.forName(dialect).tokenize(sql)
    } catch (_: TokenError) {
        return true
    }
    if (tokens.any { it.tokenType == TokenType.HINT }) return true
    return HINT_OPENERS.findAll(sql).any { match -> tokens.none { match.range.first in it.start..it.end } }
}

private val HINT_OPENERS = Regex("""/\*[+!]""")

private const val HINT = "The query has an optimizer hint, which may set session variables for it (Doris SET_VAR)."

private val STATEMENTS = setOf(
    Insert::class, Update::class, Delete::class, Merge::class, Create::class, Drop::class, Alter::class, Command::class,
    Into::class, Copy::class, TruncateTable::class, Use::class, SetStatement::class, Transaction::class, Commit::class, Rollback::class,
)

private const val PARAMETERS = "The query has parameters: resolve them with Execute before EXPLAIN."

/** The plan text of `EXPLAIN` [rows], each row's first column: one line each for Doris, one block for Trino. */
fun explainPlanText(rows: List<String?>): String = rows.joinToString("\n") { it.orEmpty() }.trimEnd()

private const val UNREADABLE = "The statement could not be read as a plain query, so it is not explained."
