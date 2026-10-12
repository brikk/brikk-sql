package dev.brikk.house.sql.intellij.completion

import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import dev.brikk.house.sql.parser.pipeStageKeywords
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus

/**
 * Where a table function stands, recognized by the words before the name being typed, ignoring
 * case: right after a phrase in [after] (`FROM`, any join ending in `JOIN`), after a comma
 * continuing a list one of them began (`FROM a, b`), and right inside the parentheses of a word in
 * [parenthesized] (Trino's `TABLE(...)`). A longer phrase in [notAfter] wins, such as ClickHouse's
 * `ARRAY JOIN`, which takes an array.
 */
@ApiStatus.Experimental
public data class TablePositions(
    val after: Set<String>,
    val parenthesized: Set<String> = emptySet(),
    val notAfter: Set<String> = emptySet(),
) {
    public companion object {
        /** After FROM, a join or a PIPE `CALL`, and after a comma in their lists. */
        public val STANDARD: TablePositions = TablePositions(after = setOf("FROM", "JOIN", "|> CALL"), notAfter = setOf("ARRAY JOIN"))
    }
}

/** What fits where completion is invoked, judged from the text before the typed name. */
internal enum class FunctionPosition {
    /** Only table functions. */
    TABLE,

    /** Everything but table functions. */
    EXPRESSION,

    /** Right after `|>`, where only a stage phrase can stand. */
    STAGE_HEAD,

    /** Not known, so everything: hiding a fitting function is worse than offering one that does not fit. */
    UNKNOWN,
    ;

    fun offers(kind: FunctionKind): Boolean {
        val table = kind == FunctionKind.TABLE_VALUED || kind == FunctionKind.TABLE_GENERATING
        return when (this) {
            TABLE -> table
            EXPRESSION -> !table
            STAGE_HEAD -> false
            UNKNOWN -> true
        }
    }
}

/** The position after [text] (the statement up to the typed name) in the core [dialect]. */
internal fun functionPosition(text: CharSequence, dialect: String, positions: TablePositions): FunctionPosition {
    val config = TokenizerConfigs.forNameOrNull(dialect) ?: return FunctionPosition.UNKNOWN
    val lexed = Tokenizer(config).tokenizeForEditor(text.toString())
    if (lexed.failureOffset != null) return FunctionPosition.UNKNOWN
    val tokens = lexed.tokens.takeLastWhile { it.tokenType != TokenType.SEMICOLON }
    val words = tokens.flatMap(Token::words)
    if (words.isEmpty()) return FunctionPosition.UNKNOWN
    val stage = words.lastIndexOf("|>")
    if (stage >= 0 && isStageHead(words.subList(stage + 1, words.size), dialect)) return FunctionPosition.STAGE_HEAD
    positions.phraseAtEnd(words)?.let { return it }
    return when (words.last()) {
        "(" -> positions.parenthesized(words, words.size - 1)
        "," -> positions.commaList(words)
        in EXPRESSION_WORDS -> FunctionPosition.EXPRESSION
        else -> FunctionPosition.UNKNOWN
    }
}

/** TABLE or UNKNOWN if a phrase of [TablePositions.after] or [TablePositions.notAfter] ends [words], the longest deciding. */
private fun TablePositions.phraseAtEnd(words: List<String>): FunctionPosition? {
    fun List<String>.matches(phrase: String) = phrase.uppercase(Locale.ROOT).split(' ').let { size >= it.size && takeLast(it.size) == it }
    val table = after.filter(words::matches).maxOfOrNull { it.split(' ').size }
    val other = notAfter.filter(words::matches).maxOfOrNull { it.split(' ').size }
    return when {
        table == null && other == null -> null
        (other ?: 0) >= (table ?: 0) -> FunctionPosition.UNKNOWN
        else -> FunctionPosition.TABLE
    }
}

/**
 * Inside the `(` at [paren]: a table after a [TablePositions.parenthesized] word; not known after a
 * word a table follows (a subquery or nested join); call arguments or a list after another name.
 */
private fun TablePositions.parenthesized(words: List<String>, paren: Int): FunctionPosition {
    val before = words.getOrNull(paren - 1) ?: return FunctionPosition.UNKNOWN
    return when {
        parenthesized.any { it.equals(before, ignoreCase = true) } -> FunctionPosition.TABLE
        before in listHeads -> FunctionPosition.UNKNOWN
        before == "'" || before.first().isLetter() || before.first() == '_' -> FunctionPosition.EXPRESSION
        else -> FunctionPosition.UNKNOWN
    }
}

/** After a comma: the clause or brackets holding the list decide, found by walking back over balanced brackets. */
private fun TablePositions.commaList(words: List<String>): FunctionPosition {
    var depth = 0
    for (index in words.indices.reversed()) {
        val word = words[index]
        when {
            word == ")" -> depth++
            word == "(" && depth > 0 -> depth--
            word == "(" -> return parenthesized(words, index).takeIf { it == FunctionPosition.EXPRESSION } ?: FunctionPosition.UNKNOWN
            depth > 0 -> {}
            word == "|>" -> return FunctionPosition.UNKNOWN
            word in listHeads -> return phraseAtEnd(words.subList(0, index + 1)) ?: FunctionPosition.TABLE
            word in CLAUSE_WORDS -> return FunctionPosition.EXPRESSION
        }
    }
    return FunctionPosition.UNKNOWN
}

/** The last words of the [TablePositions.after] phrases: the words a table list begins with. */
private val TablePositions.listHeads: Set<String>
    get() = after.mapTo(HashSet()) { it.uppercase(Locale.ROOT).substringAfterLast(' ') }

/** Whether the typed words after `|>` are not yet a whole stage phrase but begin one, or are none. */
private fun isStageHead(typed: List<String>, dialect: String): Boolean {
    val phrases = STAGE_PHRASES.getOrPut(dialect) { pipeStageKeywords(dialect).map { it.split(' ') } }
    return phrases.none { it == typed } && phrases.any { it.size > typed.size && it.subList(0, typed.size) == typed }
}

private val STAGE_PHRASES = ConcurrentHashMap<String, List<List<String>>>()

/** Upper-case words of a keyword or punctuation token; a literal or identifier is one opaque word. */
private val Token.words: List<String>
    get() = when (tokenType) {
        in OPAQUE -> listOf("'")
        else -> text.trim().split(WHITESPACE).filter(String::isNotEmpty).map { it.uppercase(Locale.ROOT) }
    }

private val OPAQUE = setOf(
    TokenType.STRING, TokenType.NUMBER, TokenType.IDENTIFIER, TokenType.BIT_STRING, TokenType.HEX_STRING,
    TokenType.BYTE_STRING, TokenType.NATIONAL_STRING, TokenType.RAW_STRING, TokenType.HEREDOC_STRING,
)

private val WHITESPACE = Regex("\\s+")

/** Clauses whose comma lists hold expressions. */
private val CLAUSE_WORDS = setOf(
    "SELECT", "WHERE", "HAVING", "BY", "SET", "VALUES", "QUALIFY", "RETURNING", "WINDOW", "LIMIT", "EXTEND", "AGGREGATE",
)

/** Words after which an expression begins. */
private val EXPRESSION_WORDS = ExpressionPositions.STANDARD.keywords + ExpressionPositions.STANDARD.operators - setOf("(", ",") +
    setOf("BY", "*", "SET", "EXTEND", "AGGREGATE", "RETURN", "ELSEIF", "QUALIFY")
