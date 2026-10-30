package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Alter
import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.ast.Commit
import dev.brikk.house.sql.ast.Copy
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.Delete
import dev.brikk.house.sql.ast.Drop
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Insert
import dev.brikk.house.sql.ast.Into
import dev.brikk.house.sql.ast.Merge
import dev.brikk.house.sql.ast.Parameter
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.Rollback
import dev.brikk.house.sql.ast.Set as SetStatement
import dev.brikk.house.sql.ast.Transaction
import dev.brikk.house.sql.ast.TruncateTable
import dev.brikk.house.sql.ast.Update
import dev.brikk.house.sql.ast.Use
import dev.brikk.house.sql.ast.Values
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType

/** Outcome of [prepareTextExplain]. */
sealed interface ExplainPreparation {
    /** Run [sql] on a connection of its own; its rows are the plan ([explainPlanText]). */
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
 * run. Only a single query is explained ([statements]), since `EXPLAIN` of other statements may change
 * data: Trino 483 creates the table of an `EXPLAIN CREATE TABLE ... AS SELECT`. A statement inside the
 * query (DML in a CTE, `SELECT ... INTO`) is refused too. Functions, UDFs and hints are the engine's
 * business, as in any query the console runs. Parameters (the dialect's, and the console's default
 * `${name}` and `#{name}`) are refused: they are resolved with Execute. Text the core parser cannot read
 * is explained when it starts like a query and holds no statement keyword, so the server reports its
 * own error; anything else unreadable is refused. PIPE syntax must be lowered first. A query starting
 * with a parenthesis is explained as a derived table, since `EXPLAIN (` opens Trino's options. DuckDB is
 * refused outright: its `EXPLAIN` evaluates table function arguments, so
 * `EXPLAIN SELECT * FROM range(nextval('s'))` advances the sequence.
 */
fun prepareTextExplain(
    statement: String,
    dialect: String,
    statements: ExplainableStatements = ExplainableStatements.Queries,
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
    val tokens = try {
        Dialects.forName(dialect).tokenize(sql)
    } catch (_: TokenError) {
        return ExplainPreparation.Refused(UNREADABLE)
    }
    val parameters = hasUserParameters(tokens) || try {
        parseParameterOccurrences(sql, dialect).isNotEmpty()
    } catch (_: ParseError) {
        tokens.any { it.tokenType == TokenType.PLACEHOLDER || it.tokenType == TokenType.PARAMETER }
    }
    if (parameters) return ExplainPreparation.Refused(PARAMETERS)
    val ast = try {
        Dialects.forName(dialect).parseOne(sql)
    } catch (_: ParseError) {
        null
    }
    when {
        ast == null -> if (!looksLikeAQuery(tokens)) return ExplainPreparation.Refused(UNREADABLE)
        statements == ExplainableStatements.Queries && ast !is Query && ast !is Values ->
            return ExplainPreparation.Refused("Only queries are explained: EXPLAIN of other statements may change data.")
        else -> ast.findAll(Expression::class).firstOrNull { it::class in STATEMENTS || it is Placeholder || it is Parameter }?.let { node ->
            return ExplainPreparation.Refused(if (node::class in STATEMENTS) "The query holds a statement (${node.key.uppercase()}), which EXPLAIN may run." else PARAMETERS)
        }
    }
    // Decided on the first token, so leading comments do not hide the parenthesis; a trailing line comment ends before the wrapper closes.
    val parenthesized = tokens.firstOrNull()?.tokenType == TokenType.L_PAREN
    return ExplainPreparation.Ready(if (parenthesized) "EXPLAIN SELECT * FROM ($sql\n) AS explained" else "EXPLAIN $sql")
}

/** Whether unparsed [tokens] start like a query and hold no keyword of a statement that may change data. */
private fun looksLikeAQuery(tokens: List<Token>): Boolean =
    tokens.firstOrNull()?.tokenType in QUERY_STARTS && tokens.none { it.tokenType in STATEMENT_KEYWORDS }

private val QUERY_STARTS = setOf(TokenType.SELECT, TokenType.WITH, TokenType.VALUES, TokenType.L_PAREN)

private val STATEMENT_KEYWORDS = setOf(
    TokenType.INSERT, TokenType.UPDATE, TokenType.DELETE, TokenType.MERGE, TokenType.CREATE, TokenType.DROP,
    TokenType.ALTER, TokenType.INTO, TokenType.TRUNCATE, TokenType.COPY, TokenType.REPLACE,
)

/** The console's default user parameters, `${name}` and `#{name}`, which the dialect reads as other tokens. */
private fun hasUserParameters(tokens: List<Token>): Boolean = tokens.zipWithNext().any { (head, brace) ->
    brace.tokenType == TokenType.L_BRACE && (head.tokenType == TokenType.HASH || head.text == "$") && head.end + 1 == brace.start
}

private val STATEMENTS = setOf(
    Insert::class, Update::class, Delete::class, Merge::class, Create::class, Drop::class, Alter::class, Command::class,
    Into::class, Copy::class, TruncateTable::class, Use::class, SetStatement::class, Transaction::class, Commit::class, Rollback::class,
    dev.brikk.house.sql.ast.ClickhouseExplain::class, dev.brikk.house.sql.ast.ClickhouseCreateToken::class,
    dev.brikk.house.sql.ast.ClickhouseUserAuthentication::class, dev.brikk.house.sql.ast.ClickhouseHandler::class,
    dev.brikk.house.sql.ast.ClickhouseHypotheticalIndex::class, dev.brikk.house.sql.ast.ClickhouseSystem::class,
    dev.brikk.house.sql.ast.SpiceExplain::class, dev.brikk.house.sql.ast.SpicePrepare::class,
    dev.brikk.house.sql.ast.SpiceExecute::class, dev.brikk.house.sql.ast.SpiceShow::class,
    dev.brikk.house.sql.ast.SpiceReset::class,
)

private const val PARAMETERS = "The query has parameters: resolve them with Execute before EXPLAIN."

/** The plan text of `EXPLAIN` [rows], each row's first column: one line each for Doris, one block for Trino. */
fun explainPlanText(rows: List<String?>): String = rows.joinToString("\n") { it.orEmpty() }.trimEnd()

private const val UNREADABLE = "The statement could not be read as a query, so it is not explained."
