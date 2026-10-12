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
 * Exact authored parameter ranges without rendering or lowering; [ParameterOccurrence.style]
 * tells IDE `:name` binds from typed server parameters. Parse/token/shape refusals
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
 * error positions address [sql] too. Typed server parameters ([ParamStyle.NAMED_TYPED]) stay.
 * Refusals propagate as in [parseParameterOccurrences].
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
         * - field types: every colon inside `STRUCT<`, `ARRAY<`, `MAP<` or `VARIANT<` type brackets,
         *   nested;
         * - slices: the first colon of a subscript after a complete operand (a name, literal,
         *   closing bracket, `END` of a CASE, or a bind), so `arr[1:n]` has no bind but `arr[:i]`
         *   and `arr[1 + :i]` do.
         *
         * Tokens cannot prove everything: a column named STRUCT, ARRAY or MAP compared with `<`
         * reads as a type, and a keyword-named column before a slice colon (`arr[date:n]`)
         * does not end an operand. A statement terminator closes every open bracket.
         *
         * Where the dialect has typed server parameters (ClickHouse), each `{name:Type}` is
         * reported as [ParamStyle.NAMED_TYPED] over its whole braces, for callers to skip.
         */
        fun parse(text: String, dialect: String, checkCanceled: () -> Unit = {}): SqlScriptParameters {
            val lexed = Tokenizer(TokenizerConfigs.forName(dialect)).tokenizeForEditor(text, checkCanceled)
            val typed = Dialects.forName(dialect).typedQueryParameters
            return SqlScriptParameters(text, dialect, colonBinds(text, lexed.tokens, typed, checkCanceled), lexed.failureOffset, lexed.error)
        }

        private enum class Bracket { PAREN, ARRAY, SUBSCRIPT, MAP, TYPE }

        private class Open(val kind: Bracket) {
            var colonSeen = false
        }

        private class OpenBrackets {
            private val stack = ArrayList<Open>()
            private val counts = IntArray(Bracket.entries.size)

            val innermost: Open? get() = stack.lastOrNull()
            val inType: Boolean get() = counts[Bracket.TYPE.ordinal] > 0

            fun push(kind: Bracket) {
                stack += Open(kind)
                counts[kind.ordinal]++
            }

            // Pops through the innermost [kinds] bracket, if one is open; each bracket pops once.
            fun close(vararg kinds: Bracket) {
                if (kinds.none { counts[it.ordinal] > 0 }) return
                do {
                    val popped = stack.removeAt(stack.lastIndex)
                    counts[popped.kind.ordinal]--
                } while (popped.kind !in kinds)
            }

            fun clear() {
                stack.clear()
                counts.fill(0)
            }
        }

        // `{` name `:` type `}`, where the type holds no brace or statement terminator.
        private fun typedParameter(text: String, tokens: List<Token>, open: Int): Pair<ParameterOccurrence, Int>? {
            val name = tokens.getOrNull(open + 1) ?: return null
            if (tokens.getOrNull(open + 2)?.tokenType != TokenType.COLON) return null
            val spelled = when {
                name.tokenType == TokenType.IDENTIFIER -> name.text
                else -> text.substring(name.start, name.end + 1).takeIf { PARAMETER_NAME.matches(it) } ?: return null
            }
            var close = open + 3
            while (close < tokens.size && tokens[close].tokenType !in TYPED_PARAMETER_STOPS) close++
            if (close == open + 3 || tokens.getOrNull(close)?.tokenType != TokenType.R_BRACE) return null
            return ParameterOccurrence(spelled, ParamStyle.NAMED_TYPED, SyntacticRange(tokens[open].start, tokens[close].end + 1),
                SyntacticRange(name.start, name.end + 1)) to close
        }

        private fun colonBinds(text: String, tokens: List<Token>, typed: Boolean, checkCanceled: () -> Unit): List<ParameterOccurrence> {
            val open = OpenBrackets()
            val binds = ArrayList<ParameterOccurrence>()
            var bindNameEnd = -1
            var skipThrough = -1
            fun endsOperand(previous: Token?) = previous != null && (previous.tokenType in OPERAND_ENDS || previous.end == bindNameEnd)
            for ((index, token) in tokens.withIndex()) {
                if (index and 1023 == 1023) checkCanceled()
                if (index <= skipThrough) continue
                val previous = tokens.getOrNull(index - 1)
                when (token.tokenType) {
                    TokenType.SEMICOLON -> open.clear()
                    TokenType.L_PAREN -> open.push(Bracket.PAREN)
                    TokenType.L_BRACKET -> open.push(if (endsOperand(previous)) Bracket.SUBSCRIPT else Bracket.ARRAY)
                    TokenType.L_BRACE -> {
                        val parameter = if (typed) typedParameter(text, tokens, index) else null
                        if (parameter != null) {
                            binds += parameter.first
                            skipThrough = parameter.second
                        } else open.push(Bracket.MAP)
                    }
                    TokenType.LT -> if (open.innermost?.kind == Bracket.TYPE || previous?.text?.uppercase() in TYPE_CONSTRUCTORS) {
                        open.push(Bracket.TYPE)
                    }
                    TokenType.GT -> if (open.innermost?.kind == Bracket.TYPE) open.close(Bracket.TYPE)
                    TokenType.R_PAREN -> open.close(Bracket.PAREN)
                    TokenType.R_BRACKET -> open.close(Bracket.ARRAY, Bracket.SUBSCRIPT)
                    TokenType.R_BRACE -> open.close(Bracket.MAP)
                    TokenType.COMMA -> open.innermost?.takeIf { it.kind == Bracket.MAP }?.colonSeen = false
                    TokenType.COLON -> {
                        val innermost = open.innermost
                        val syntax = when {
                            open.inType -> true
                            innermost == null || innermost.colonSeen -> false
                            innermost.kind == Bracket.MAP -> true
                            innermost.kind == Bracket.SUBSCRIPT -> endsOperand(previous)
                            else -> false
                        }
                        if (syntax) innermost?.colonSeen = true
                        val name = tokens.getOrNull(index + 1)
                        if (!syntax && name != null && name.start == token.end + 1 && text.getOrNull(token.start - 1) != ':') {
                            val spelled = boundName(text.substring(name.start, name.end + 1))
                            if (spelled != null) {
                                val nameEnd = name.start + spelled.length
                                binds += ParameterOccurrence(spelled, ParamStyle.NAMED_COLON,
                                    SyntacticRange(token.start, nameEnd), SyntacticRange(name.start, nameEnd))
                                bindNameEnd = nameEnd - 1
                            }
                        }
                    }
                    else -> {}
                }
            }
            return binds
        }

        // A fused multiword keyword (`double precision`) binds only its first word.
        private fun boundName(spelled: String): String? {
            val word = PARAMETER_NAME.matchAt(spelled, 0)?.value ?: return null
            return word.takeIf { it.length == spelled.length || spelled[it.length].isWhitespace() }
        }

        private val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val TYPED_PARAMETER_STOPS = setOf(TokenType.L_BRACE, TokenType.R_BRACE, TokenType.SEMICOLON)
        private val TYPE_CONSTRUCTORS = setOf("STRUCT", "ARRAY", "MAP", "VARIANT")
        private val OPERAND_ENDS = setOf(
            TokenType.VAR, TokenType.IDENTIFIER, TokenType.NUMBER, TokenType.STRING, TokenType.NATIONAL_STRING,
            TokenType.RAW_STRING, TokenType.HEREDOC_STRING, TokenType.BIT_STRING, TokenType.HEX_STRING,
            TokenType.BYTE_STRING, TokenType.UNICODE_STRING, TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE,
            TokenType.END, TokenType.NULL, TokenType.TRUE, TokenType.FALSE,
        )
    }
}
