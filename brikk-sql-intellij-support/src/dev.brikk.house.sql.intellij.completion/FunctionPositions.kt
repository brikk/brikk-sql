package dev.brikk.house.sql.intellij.completion

import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import java.util.Locale
import org.jetbrains.annotations.ApiStatus

/**
 * Where a table function stands, recognized by the keywords before the name being typed, ignoring
 * case: right after a phrase in [after] (`FROM`, any join ending in `JOIN`), after a comma
 * continuing a list one of them began (`FROM a, b`), and right inside the parentheses of a word in
 * [parenthesized] (`TABLE(...)`). A longer phrase in [notAfter] wins, such as `IS DISTINCT FROM` or
 * ClickHouse's `ARRAY JOIN`. A `FROM` inside a call that holds no query (`EXTRACT(YEAR FROM x)`) is
 * an argument, not a table position.
 *
 * Table functions named in [inExpressions] or of a kind in [kindsInExpressions] are also offered in
 * expressions. With [wrapper], a table function other than those in [unwrapped] is inserted as
 * `wrapper(name(...))` where it stands bare, as Trino requires `TABLE(...)` around them.
 */
@ApiStatus.Experimental
public data class TablePositions(
    val after: Set<String>,
    val parenthesized: Set<String> = emptySet(),
    val notAfter: Set<String> = emptySet(),
    val inExpressions: Set<String> = emptySet(),
    val kindsInExpressions: Set<FunctionKind> = emptySet(),
    val wrapper: String? = null,
    val unwrapped: Set<String> = emptySet(),
) {
    internal fun fitsExpressions(entry: FunctionPresentation): Boolean =
        entry.kind in kindsInExpressions || inExpressions.any { it.equals(entry.name, ignoreCase = true) }

    internal fun wraps(entry: FunctionPresentation): Boolean =
        wrapper != null && unwrapped.none { it.equals(entry.name, ignoreCase = true) }

    public companion object {
        /** After FROM, a join or a PIPE `CALL`, a comma in their lists, and inside `TABLE(`. */
        public val STANDARD: TablePositions = TablePositions(
            after = setOf("FROM", "JOIN", "|> CALL"),
            parenthesized = setOf("TABLE"),
            notAfter = setOf("DISTINCT FROM", "ARRAY JOIN"),
        )

        /** [STANDARD] with what the engine adds, for the dialects with recorded evidence. */
        public fun of(dialect: String): TablePositions = when (dialect.lowercase(Locale.ROOT)) {
            // `SELECT unnest([1, 2])` runs; every other DuckDB table function is FROM-only.
            "duckdb" -> STANDARD.copy(inExpressions = setOf("unnest"))
            // UNNEST and JSON_TABLE are relations of their own and stand bare (TABLE(JSON_TABLE(...)) does
            // not parse); polymorphic table functions such as sequence and exclude_columns need TABLE(...).
            "trino" -> STANDARD.copy(wrapper = "TABLE", unwrapped = setOf("unnest", "json_table"))
            // Generators (explode) follow LATERAL VIEW and are not proven illegal in SELECT.
            "doris" -> STANDARD.copy(after = STANDARD.after + "LATERAL VIEW", kindsInExpressions = setOf(FunctionKind.TABLE_GENERATING))
            else -> STANDARD
        }
    }
}

/** What fits where completion is invoked, judged from the text before the typed name. */
internal enum class FunctionPosition {
    /** Only table functions, standing bare. */
    TABLE,

    /** Only table functions, inside a [TablePositions.parenthesized] word's parentheses. */
    TABLE_ARGUMENT,

    /** Everything but the table functions not legal in expressions. */
    EXPRESSION,

    /** Right after `|>`, where only a stage phrase can stand. */
    STAGE_HEAD,

    /** Not known, so everything: hiding a fitting function is worse than offering one that does not fit. */
    UNKNOWN,
    ;

    fun offers(entry: FunctionPresentation, positions: TablePositions): Boolean {
        val table = entry.kind == FunctionKind.TABLE_VALUED || entry.kind == FunctionKind.TABLE_GENERATING
        return when (this) {
            TABLE, TABLE_ARGUMENT -> table
            EXPRESSION -> !table || positions.fitsExpressions(entry)
            STAGE_HEAD -> false
            UNKNOWN -> true
        }
    }
}

/**
 * The position after [tokens], the core tokens of the statement before the typed name
 * (`SqlNameContext.tokens`). [stagePhrases] are the PIPE stages that may follow `|>`;
 * [isFunction] tells a call such as `range(` from a keyword when the name tokenizes as one.
 */
internal fun functionPosition(
    tokens: List<Token>,
    positions: TablePositions,
    stagePhrases: List<String>,
    isFunction: (String) -> Boolean,
): FunctionPosition {
    val words = tokens.takeLastWhile { it.tokenType != TokenType.SEMICOLON }.flatMap(Token::words)
    if (words.isEmpty()) return FunctionPosition.UNKNOWN
    val stage = words.indexOfLast { it.text == "|>" }
    if (stage >= 0 && isStageHead(words.subList(stage + 1, words.size), stagePhrases)) return FunctionPosition.STAGE_HEAD
    val context = Context(words, positions, isFunction)
    positions.phraseAtEnd(words)?.let { return if (it == FunctionPosition.TABLE) context.tableUnlessCallArgument() else it }
    return when (words.last().text) {
        "(" -> context.parenthesized(words.size - 1)
        "," -> context.commaList()
        else -> if (words.startsExpression(words.size - 1)) FunctionPosition.EXPRESSION else FunctionPosition.UNKNOWN
    }
}

private class Word(val text: String, val type: TokenType) {
    /** An identifier rather than a keyword, such as a table named `aggregate`. */
    val isName: Boolean get() = type == TokenType.VAR || type == TokenType.IDENTIFIER
}

/** TABLE or UNKNOWN if a keyword phrase of [TablePositions.after] or [TablePositions.notAfter] ends [words], the longest deciding. */
private fun TablePositions.phraseAtEnd(words: List<Word>): FunctionPosition? {
    fun matches(phrase: String): Boolean {
        val parts = phrase.uppercase(Locale.ROOT).split(' ')
        return words.size >= parts.size && words.takeLast(parts.size).let { tail ->
            tail.map(Word::text) == parts && tail.none(Word::isName)
        }
    }
    val table = after.filter(::matches).maxOfOrNull { it.split(' ').size }
    val other = notAfter.filter(::matches).maxOfOrNull { it.split(' ').size }
    return when {
        table == null && other == null -> null
        (other ?: 0) >= (table ?: 0) -> FunctionPosition.UNKNOWN
        else -> FunctionPosition.TABLE
    }
}

/** The words before the typed name, with what decides between them. */
private class Context(val words: List<Word>, val positions: TablePositions, val isFunction: (String) -> Boolean) {
    /** A name, or a function name that tokenizes as a keyword, before a `(` opens a call. */
    fun isCall(word: Word): Boolean = word.isName || isFunction(word.text)

    /**
     * TABLE, unless the table keyword sits in an unclosed call holding no query, as `FROM` does in
     * `EXTRACT(YEAR FROM x)`: then an argument, or not known when the call is not recognized.
     */
    fun tableUnlessCallArgument(): FunctionPosition {
        val paren = enclosingParen() ?: return FunctionPosition.TABLE
        val inside = words.subList(paren + 1, words.size)
        if (inside.firstOrNull()?.text == "FROM" || inside.atTopLevel().any { it.text in QUERY_HEADS && !it.isName }) return FunctionPosition.TABLE
        val before = words.getOrNull(paren - 1) ?: return FunctionPosition.UNKNOWN
        return if (isCall(before)) FunctionPosition.EXPRESSION else FunctionPosition.UNKNOWN
    }

    /** The words outside every bracket pair these words open and close, such as a nested `(SELECT ...)`. */
    private fun List<Word>.atTopLevel(): List<Word> {
        var depth = 0
        return filter { word ->
            when (word.text) {
                "(" -> depth++
                ")" -> depth--
            }
            depth == 0 && word.text != ")"
        }
    }

    /** The index of the innermost `(` still open at the end. */
    private fun enclosingParen(): Int? {
        var depth = 0
        for (index in words.indices.reversed()) {
            when (words[index].text) {
                ")" -> depth++
                "(" -> if (depth == 0) return index else depth--
            }
        }
        return null
    }

    /**
     * Inside the `(` at [paren]: a table argument after a [TablePositions.parenthesized] keyword, call
     * arguments after a call or a list after `IN`, otherwise not known (`LATERAL (`, `FROM (`).
     */
    fun parenthesized(paren: Int): FunctionPosition {
        val before = words.getOrNull(paren - 1) ?: return FunctionPosition.UNKNOWN
        return when {
            !before.isName && positions.parenthesized.any { it.equals(before.text, ignoreCase = true) } -> FunctionPosition.TABLE_ARGUMENT
            isCall(before) || before.text == "IN" -> FunctionPosition.EXPRESSION
            else -> FunctionPosition.UNKNOWN
        }
    }

    /** After a comma: the clause or brackets holding the list decide, found by walking back over balanced brackets. */
    fun commaList(): FunctionPosition {
        val heads = positions.after.mapTo(HashSet()) { it.uppercase(Locale.ROOT).substringAfterLast(' ') }
        var depth = 0
        for (index in words.indices.reversed()) {
            val word = words[index]
            when {
                word.text == ")" -> depth++
                word.text == "(" && depth > 0 -> depth--
                word.text == "(" -> return parenthesized(index).takeIf { it == FunctionPosition.EXPRESSION } ?: FunctionPosition.UNKNOWN
                depth > 0 -> {}
                word.text == "|>" -> return FunctionPosition.UNKNOWN
                word.text in heads && !word.isName ->
                    return positions.phraseAtEnd(words.subList(0, index + 1))?.takeIf { it != FunctionPosition.TABLE } ?: tableUnlessCallArgument()
                word.text in CLAUSES && !word.isName || word.text in PIPE_EXPRESSION_STAGES && words.getOrNull(index - 1)?.text == "|>" ->
                    return FunctionPosition.EXPRESSION
            }
        }
        return FunctionPosition.UNKNOWN
    }
}

/** Whether an expression begins after the word at [index]: an operator, an expression keyword, or an expression PIPE stage. */
private fun List<Word>.startsExpression(index: Int): Boolean {
    val word = this[index]
    return when (word.text) {
        in PIPE_EXPRESSION_STAGES -> getOrNull(index - 1)?.text == "|>"
        in OPERATORS -> true
        else -> word.text in EXPRESSION_KEYWORDS && !word.isName
    }
}

/** Whether the typed words after `|>` are not yet a whole stage phrase but begin one, or are none. */
private fun isStageHead(typed: List<Word>, stagePhrases: List<String>): Boolean {
    val words = typed.map(Word::text)
    val phrases = stagePhrases.map { it.uppercase(Locale.ROOT).split(' ') }
    return phrases.none { it == words } && phrases.any { it.size > words.size && it.subList(0, words.size) == words }
}

/** Upper-case words of a keyword or punctuation token; a literal or quoted identifier is one opaque word. */
private val Token.words: List<Word>
    get() = when (tokenType) {
        in OPAQUE -> listOf(Word("'", tokenType))
        else -> text.trim().split(WHITESPACE).filter(String::isNotEmpty).map { Word(it.uppercase(Locale.ROOT), tokenType) }
    }

private val OPAQUE = setOf(
    TokenType.STRING, TokenType.NUMBER, TokenType.IDENTIFIER, TokenType.BIT_STRING, TokenType.HEX_STRING,
    TokenType.BYTE_STRING, TokenType.NATIONAL_STRING, TokenType.RAW_STRING, TokenType.HEREDOC_STRING,
)

private val WHITESPACE = Regex("\\s+")

private val QUERY_HEADS = setOf("SELECT", "WITH", "VALUES")

/** Clauses whose comma lists hold expressions. */
private val CLAUSES = setOf("SELECT", "WHERE", "HAVING", "BY", "SET", "VALUES", "QUALIFY", "RETURNING", "WINDOW", "LIMIT")

/** PIPE stages taking expressions, which tokenize as names. */
private val PIPE_EXPRESSION_STAGES = setOf("EXTEND", "AGGREGATE")

private val OPERATORS = ExpressionPositions.STANDARD.operators - setOf("(", ",") + "*"

private val EXPRESSION_KEYWORDS = ExpressionPositions.STANDARD.keywords + setOf("BY", "SET", "RETURN", "QUALIFY")
