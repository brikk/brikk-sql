package dev.brikk.house.sql.intellij.completion

import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs

/**
 * What the source text before a typed name says, read with the core dialect's tokens rather than
 * PSI: a grammar may build no reference (PIPE text, lenient statements), and a span the dialect's
 * lexer masks is a comment in PSI but code in the source.
 */
internal class SourceContext(
    /** The statement holding the typed name, up to it. */
    val statement: String,
    /** Whether the name stands in code, not in a string, comment or inline data. */
    val inCode: Boolean,
    /** The dotted qualifier written right before the name, as written (`db."t"`), or null. */
    val qualifier: String?,
    /** The last token before the name, or null. */
    val previous: String?,
)

/** The context of a name typed right after [before] in the core [dialect]; null for a dialect core does not know. */
internal fun sourceContext(before: String, dialect: String, checkCanceled: () -> Unit): SourceContext? {
    val config = TokenizerConfigs.forNameOrNull(dialect) ?: return null
    val start = SqlScriptSnapshot.parse(before, dialect, checkCanceled).statements.lastOrNull()?.start ?: 0
    val statement = before.substring(start)
    // A name typed here is code exactly when core reads it as its own token.
    val lexed = Tokenizer(config).tokenizeForEditor(statement + PROBE, checkCanceled)
    val probe = lexed.tokens.lastOrNull()
    if (lexed.failureOffset != null || probe == null || probe.start != statement.length || probe.text != PROBE) {
        return SourceContext(statement, inCode = false, qualifier = null, previous = null)
    }
    val tokens = lexed.tokens.subList(0, lexed.tokens.size - 1)
    return SourceContext(statement, inCode = true, qualifier = qualifier(statement, tokens), previous = tokens.lastOrNull()?.text)
}

/** The adjacent `name.name.` chain ending right before the probe, without its last dot. */
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
