package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Semicolon
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs

/**
 * Exact authored parameter ranges without rendering or lowering. Parse/token/shape refusals
 * propagate: callers must not treat an unparseable input as proof of no parameters.
 * IDE-configured textual substitutions remain a separate platform concern.
 */
fun parseParameterOccurrences(sql: String, dialect: String): List<ParameterOccurrence> {
    val source = Dialects.forName(dialect)
    val tokens = source.tokenize(sql)
    val parser = source.parser().also { it.captureSourceRanges = true }
    val statements = parser.parse(tokens, sql).filterNotNull().filterNot { it is Semicolon }
    if (statements.size != 1) throw ShapeError("Parameter discovery requires exactly one statement")
    return parameterOccurrences(statements.single(), tokens)
}

/**
 * [sql] with every named placeholder ([ParamStyle.NAMED_COLON] in any spelling the dialect
 * parses) replaced by the scalar `0` padded with spaces, so a native parser can check the
 * statement before substitution. Offsets, length and line breaks are unchanged, so native
 * error positions address [sql] too. Refusals propagate as in [parseParameterOccurrences].
 */
fun maskNamedParameters(sql: String, dialect: String): String {
    val masked = StringBuilder(sql)
    for (parameter in parseParameterOccurrences(sql, dialect)) {
        if (parameter.style != ParamStyle.NAMED_COLON) continue
        val (start, end) = parameter.range
        masked[start] = '0'
        for (index in start + 1 until end) if (sql[index] != '\r' && sql[index] != '\n') masked[index] = ' '
    }
    return masked.toString()
}

/**
 * Editor-mode `:name` discovery over a whole script or incomplete input, for highlighting and
 * completion, never as execution input (that is [parseParameterOccurrences]). Occurrences are
 * found only before the first lexical failure ([failureOffset], as in [SqlScriptSnapshot]),
 * so an unterminated string, comment or quoted identifier never yields a phantom bind.
 * Offsets are UTF-16, half-open.
 */
data class SqlScriptParameters(
    val text: String,
    val dialect: String,
    val occurrences: List<ParameterOccurrence>,
    val failureOffset: Int? = null,
    val error: String? = null,
) {
    companion object {
        /**
         * A bind is `:` directly followed by an ASCII name token (`[A-Za-z_][A-Za-z0-9_]*`,
         * keywords included), not preceded by another `:`. Like strict mode, colon syntax is
         * excluded as far as tokens prove it:
         * - map/struct literal keys: the first colon of each `{...}` entry separates key and
         *   value, so `MAP{'k':v}` has no bind but `{'k': :p}` does;
         * - field types: every colon inside `STRUCT<`, `ARRAY<` or `MAP<` type brackets, nested;
         * - slices: the first colon of a subscript after a complete operand, so `arr[1:n]` has no
         *   bind but `arr[:i]` and `arr[1 + :i]` do.
         *
         * Tokens cannot prove everything: a column named STRUCT, ARRAY or MAP compared with `<`
         * reads as a type, and a keyword-named column before a slice colon (`arr[date:n]`)
         * does not end an operand. A statement terminator closes every open bracket.
         */
        fun parse(text: String, dialect: String): SqlScriptParameters {
            val lexed = Tokenizer(TokenizerConfigs.forName(dialect)).tokenizeForEditor(text)
            return SqlScriptParameters(text, dialect, colonBinds(text, lexed.tokens), lexed.failureOffset, lexed.error)
        }

        private enum class Bracket { PAREN, ARRAY, SUBSCRIPT, MAP, TYPE }

        private class Open(val kind: Bracket) {
            var colonSeen = false
        }

        private fun colonBinds(text: String, tokens: List<Token>): List<ParameterOccurrence> {
            val open = ArrayList<Open>()
            val binds = ArrayList<ParameterOccurrence>()
            fun close(vararg kinds: Bracket) {
                val index = open.indexOfLast { it.kind in kinds }
                if (index >= 0) while (open.size > index) open.removeAt(open.lastIndex)
            }
            for ((index, token) in tokens.withIndex()) {
                val previous = tokens.getOrNull(index - 1)
                when (token.tokenType) {
                    TokenType.SEMICOLON -> open.clear()
                    TokenType.L_PAREN -> open.add(Open(Bracket.PAREN))
                    TokenType.L_BRACKET -> open.add(Open(if (previous.endsOperand()) Bracket.SUBSCRIPT else Bracket.ARRAY))
                    TokenType.L_BRACE -> open.add(Open(Bracket.MAP))
                    TokenType.LT -> if (open.lastOrNull()?.kind == Bracket.TYPE || previous?.text?.uppercase() in TYPE_CONSTRUCTORS) {
                        open.add(Open(Bracket.TYPE))
                    }
                    TokenType.GT -> if (open.lastOrNull()?.kind == Bracket.TYPE) open.removeAt(open.lastIndex)
                    TokenType.R_PAREN -> close(Bracket.PAREN)
                    TokenType.R_BRACKET -> close(Bracket.ARRAY, Bracket.SUBSCRIPT)
                    TokenType.R_BRACE -> close(Bracket.MAP)
                    TokenType.COMMA -> open.lastOrNull()?.takeIf { it.kind == Bracket.MAP }?.colonSeen = false
                    TokenType.COLON -> {
                        val innermost = open.lastOrNull()
                        val syntax = when {
                            open.any { it.kind == Bracket.TYPE } -> true
                            innermost == null || innermost.colonSeen -> false
                            innermost.kind == Bracket.MAP -> true
                            innermost.kind == Bracket.SUBSCRIPT -> previous.endsOperand()
                            else -> false
                        }
                        if (syntax) innermost?.colonSeen = true
                        val name = tokens.getOrNull(index + 1)
                        if (!syntax && name != null && name.start == token.end + 1 && text.getOrNull(token.start - 1) != ':') {
                            val spelled = text.substring(name.start, name.end + 1)
                            if (PARAMETER_NAME.matches(spelled)) binds += ParameterOccurrence(spelled, ParamStyle.NAMED_COLON,
                                SyntacticRange(token.start, name.end + 1), SyntacticRange(name.start, name.end + 1))
                        }
                    }
                    else -> {}
                }
            }
            return binds
        }

        private fun Token?.endsOperand(): Boolean = this != null && tokenType in OPERAND_ENDS

        private val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val TYPE_CONSTRUCTORS = setOf("STRUCT", "ARRAY", "MAP")
        private val OPERAND_ENDS = setOf(
            TokenType.VAR, TokenType.IDENTIFIER, TokenType.NUMBER, TokenType.STRING, TokenType.NATIONAL_STRING,
            TokenType.RAW_STRING, TokenType.HEREDOC_STRING, TokenType.BIT_STRING, TokenType.HEX_STRING,
            TokenType.BYTE_STRING, TokenType.UNICODE_STRING, TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE,
        )
    }
}
