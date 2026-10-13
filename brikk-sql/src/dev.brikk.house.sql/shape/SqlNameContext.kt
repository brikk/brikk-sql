package dev.brikk.house.sql.shape

import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs

/**
 * brikk-native: what the source text before a name being typed says, read from the dialect's
 * tokens (editors use it where their own tree may not reflect the source: text their grammar does
 * not parse, or spans their lexer masks).
 */
data class SqlNameContext(
    /** The statement holding the name, up to it. */
    val statement: String,
    /** Whether the name stands in code, not in a string, comment or inline data. */
    val inCode: Boolean,
    /** The dotted qualifier written right before the name, as written (`db."t"`), or null. */
    val qualifier: String?,
    /** The statement's tokens before the name; empty outside code. */
    val tokens: List<Token>,
) {
    /** The last token's text before the name, or null. */
    val previous: String? get() = tokens.lastOrNull()?.text

    companion object {
        /**
         * The context of a name typed right after [before] in [dialect]; null for a dialect core
         * does not know. A name is in code exactly when the dialect reads it as its own token, so
         * a lexical failure before it (an unterminated string or comment) leaves it outside code.
         */
        fun of(before: String, dialect: String, checkCanceled: () -> Unit = {}): SqlNameContext? {
            val config = TokenizerConfigs.forNameOrNull(dialect) ?: return null
            val start = SqlScriptSnapshot.parse(before, dialect, checkCanceled).statements.lastOrNull()?.start ?: 0
            val statement = before.substring(start)
            val lexed = Tokenizer(config).tokenizeForEditor(statement + PROBE, checkCanceled)
            val probe = lexed.tokens.lastOrNull()
            if (lexed.failureOffset != null || probe == null || probe.start != statement.length || probe.text != PROBE) {
                return SqlNameContext(statement, inCode = false, qualifier = null, tokens = emptyList())
            }
            val tokens = lexed.tokens.subList(0, lexed.tokens.size - 1)
            return SqlNameContext(statement, inCode = true, qualifier = qualifier(statement, tokens), tokens = tokens)
        }
    }
}

/** The adjacent `name.name.` chain ending right before the name, without its last dot. */
private fun qualifier(statement: String, tokens: List<Token>): String? {
    var index = tokens.lastIndex
    var next = statement.length
    var first: Token? = null
    while (index >= 1) {
        val dot = tokens[index]
        val name = tokens[index - 1]
        if (dot.tokenType != TokenType.DOT || dot.end + 1 != next || name.end + 1 != dot.start || !name.isName) break
        first = name
        next = name.start
        index -= 2
    }
    return first?.let { statement.substring(it.start, statement.length - 1) }
}

private val Token.isName: Boolean
    get() = tokenType == TokenType.IDENTIFIER || text.firstOrNull()?.let { it.isLetter() || it == '_' } == true

private const val PROBE = "brikk_completion_probe"
