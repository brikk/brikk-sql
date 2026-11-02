package dev.brikk.house.sql.shape

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer

/** Syntax-only call context, including incomplete calls and named labels. Offsets are UTF-16. */
data class DatafusionCallSite(
    val name: String,
    val start: Int,
    val argument: Int,
    val argumentStart: Int,
    val firstArgument: String?,
    val namedArgument: String?,
    val usedNames: Set<String>,
    val atArgumentStart: Boolean,
    val role: DatafusionArgumentRole,
)

enum class DatafusionArgumentRole { EXPRESSION, DATASET, COLUMN, SEARCH_INPUT }

/** Null inside strings/comments or outside a call. Qualified calls are user-owned, not native contracts. */
fun datafusionCallSite(sql: String, offset: Int, dialect: String, checkCanceled: () -> Unit = {}): DatafusionCallSite? {
    if (dialect !in setOf("spice", "datafusion") || offset !in 0..sql.length) return null
    val text = sql.substring(0, offset)
    val lexed = Tokenizer(Dialects.forName(dialect).tokenizerConfig).tokenizeForEditor(text, checkCanceled)
    if (lexed.failureOffset != null) return null
    val tokens = lexed.tokens
    if (tokens.lastOrNull()?.let { it.end + 1 < offset && text.substring(it.end + 1).trimStart().startsWith("--") } == true) return null
    class Frame(val close: TokenType, val name: String?, val start: Int, var argumentStart: Int, var tokenStart: Int) {
        var argument = 0
        var first: String? = null
        val names = linkedSetOf<String>()
    }
    val stack = ArrayDeque<Frame>()
    fun label(frame: Frame, end: Int): String? = tokens.subList(frame.tokenStart, end).let { part ->
        part.getOrNull(0)?.text?.takeIf { part.getOrNull(1)?.text == "=>" }
    }
    for ((i, token) in tokens.withIndex()) {
        if (i and 255 == 0) checkCanceled()
        when (token.tokenType) {
            TokenType.SEMICOLON -> stack.clear()
            TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> {
                val before = tokens.getOrNull(i - 1)
                val name = if (token.tokenType == TokenType.L_PAREN && before != null &&
                    before.text.firstOrNull()?.let { it.isLetter() || it == '_' } == true &&
                    before.tokenType != TokenType.IDENTIFIER && tokens.getOrNull(i - 2)?.tokenType != TokenType.DOT) before.text.lowercase() else null
                val close = when (token.tokenType) { TokenType.L_PAREN -> TokenType.R_PAREN; TokenType.L_BRACKET -> TokenType.R_BRACKET; else -> TokenType.R_BRACE }
                stack.addLast(Frame(close, name, before?.start ?: token.start, token.end + 1, i + 1))
            }
            TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> {
                if (stack.lastOrNull()?.close != token.tokenType) return null
                stack.removeLast()
            }
            TokenType.COMMA -> stack.lastOrNull()?.let { frame ->
                if (frame.argument == 0) frame.first = text.substring(frame.argumentStart, token.start).trim()
                label(frame, i)?.let(frame.names::add)
                frame.argument++
                frame.argumentStart = token.end + 1
                frame.tokenStart = i + 1
            }
            else -> {}
        }
    }
    val frame = stack.lastOrNull() ?: return null
    val name = frame.name ?: return null
    val named = label(frame, tokens.size)
    val current = tokens.drop(frame.tokenStart)
    val first = frame.first ?: if (frame.argument > 0) null else text.substring(frame.argumentStart).trim()
    val role = if (dialect != "spice") DatafusionArgumentRole.EXPRESSION else when {
        name in setOf("vector_search", "text_search") && frame.argument == 0 && named == null -> DatafusionArgumentRole.DATASET
        name in setOf("vector_search", "text_search") && (named == "column" || frame.argument == 2 && named == null) -> DatafusionArgumentRole.COLUMN
        name == "rrf" && named == null || name == "rerank" && frame.argument == 0 && named == null -> DatafusionArgumentRole.SEARCH_INPUT
        else -> DatafusionArgumentRole.EXPRESSION
    }
    return DatafusionCallSite(name, frame.start, frame.argument, frame.argumentStart, first, named,
        frame.names + listOfNotNull(named), current.isEmpty(), role)
}
