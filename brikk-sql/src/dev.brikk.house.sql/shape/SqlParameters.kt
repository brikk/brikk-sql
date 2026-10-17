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
 * Offsets are UTF-16, half-open. [ambiguous] holds the `?` tokens cannot prove to be a bind or
 * an operator (see [parse]).
 */
data class SqlScriptParameters(
    val text: String,
    val dialect: String,
    val occurrences: List<ParameterOccurrence>,
    val failureOffset: Int? = null,
    val error: String? = null,
    val ambiguous: List<SyntacticRange> = emptyList(),
) {
    // Binary compatibility (AGENTS.md): the constructor and copy from before ambiguous.
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(text: String, dialect: String, occurrences: List<ParameterOccurrence>, failureOffset: Int? = null, error: String? = null) :
        this(text, dialect, occurrences, failureOffset, error, emptyList())

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    fun copy(
        text: String = this.text,
        dialect: String = this.dialect,
        occurrences: List<ParameterOccurrence> = this.occurrences,
        failureOffset: Int? = this.failureOffset,
        error: String? = this.error,
    ): SqlScriptParameters = copy(text, dialect, occurrences, failureOffset, error, ambiguous)

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
         * does not end an operand. A statement terminator closes every open bracket, and a
         * half-open partition range (`VALUES [(a), (b))`, where the dialect has one) closes with `)`.
         *
         * Where the dialect has typed server parameters (ClickHouse), each `{name:Type}` is
         * reported as [ParamStyle.NAMED_TYPED] over its whole braces, for callers to skip.
         *
         * With [positional], each `?` bind is reported too, as [ParamStyle.POSITIONAL] without a name,
         * including the `?` of a cast `?::Type` (one token); the type a `::` cast names ends its operand.
         * Where `?` is also the ternary operator (ClickHouse `cond ? a : b`, as the IDE's ClickHouse
         * grammar reads it), a `?` after a complete operand is the operator and opens a ternary,
         * and a `:` after a complete operand closes the nearest open one in the same brackets and is
         * never a bind (`x ? y :z`); a `?` or `:name` in operand position stays a bind (`x ? ? : :c`).
         * A `?` that tokens cannot prove is reported in [ambiguous] and not as a bind: a ternary
         * left open at its statement's or brackets' end (`SELECT x ?`), or one with an empty middle
         * (`x ?:y`); the IDE calls such a `?` a parameter. Ternaries are told apart without
         * [positional] too, so their colons are never reported as binds.
         */
        fun parse(text: String, dialect: String, checkCanceled: () -> Unit = {}): SqlScriptParameters = parse(text, dialect, false, checkCanceled)

        /** [parse], with each `?` bind reported when [positional]. */
        fun parse(text: String, dialect: String, positional: Boolean, checkCanceled: () -> Unit = {}): SqlScriptParameters {
            val lexed = Tokenizer(TokenizerConfigs.forName(dialect)).tokenizeForEditor(text, checkCanceled)
            val source = Dialects.forName(dialect)
            val typeTokens = if (source.typedQueryParameters) source.parser().typeTokens else null
            val found = binds(text, lexed.tokens, typeTokens, source.halfOpenPartitionRanges, source.questionMarkTernary, positional, checkCanceled)
            return SqlScriptParameters(text, dialect, found.first, lexed.failureOffset, lexed.error, if (positional) found.second else emptyList())
        }

        private enum class Bracket { PAREN, ARRAY, SUBSCRIPT, MAP, TYPE, RANGE }

        private class Open(val kind: Bracket) {
            var colonSeen = false
        }

        private class OpenBrackets {
            private val stack = ArrayList<Open>()
            private val counts = IntArray(Bracket.entries.size)

            val innermost: Open? get() = stack.lastOrNull()
            val depth: Int get() = stack.size
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

        // `{` name `:` type `}`, where the type is a type keyword (or `Identifier`) with optional
        // balanced, colon-free arguments; anything else stays a map literal.
        private fun typedParameter(text: String, tokens: List<Token>, open: Int, typeTokens: Set<TokenType>): Pair<ParameterOccurrence, Int>? {
            val name = tokens.getOrNull(open + 1) ?: return null
            if (tokens.getOrNull(open + 2)?.tokenType != TokenType.COLON) return null
            val spelled = when {
                name.tokenType == TokenType.IDENTIFIER -> name.text
                else -> text.substring(name.start, name.end + 1).takeIf { PARAMETER_NAME.matches(it) } ?: return null
            }
            val type = tokens.getOrNull(open + 3) ?: return null
            if (type.tokenType !in typeTokens && !type.text.equals("Identifier", ignoreCase = true)) return null
            var close = open + 4
            if (tokens.getOrNull(close)?.tokenType == TokenType.L_PAREN) {
                var depth = 0
                do {
                    when (tokens.getOrNull(close)?.tokenType) {
                        TokenType.L_PAREN -> depth++
                        TokenType.R_PAREN -> depth--
                        null, in TYPE_ARGUMENT_STOPS -> return null
                        else -> {}
                    }
                    close++
                } while (depth > 0)
            }
            if (tokens.getOrNull(close)?.tokenType != TokenType.R_BRACE) return null
            return ParameterOccurrence(spelled, ParamStyle.NAMED_TYPED, SyntacticRange(tokens[open].start, tokens[close].end + 1),
                SyntacticRange(name.start, name.end + 1)) to close
        }

        private fun binds(
            text: String,
            tokens: List<Token>,
            typeTokens: Set<TokenType>?,
            halfOpenRanges: Boolean,
            ternary: Boolean,
            positional: Boolean,
            checkCanceled: () -> Unit,
        ): Pair<List<ParameterOccurrence>, List<SyntacticRange>> {
            val open = OpenBrackets()
            val binds = ArrayList<ParameterOccurrence>()
            val ambiguous = ArrayList<SyntacticRange>()
            // Each open ternary's `?` with the bracket depth it stands at.
            val ternaries = ArrayList<Pair<Token, Int>>()
            var bindNameEnd = -1
            var skipThrough = -1
            // The type a `::` cast names ends its operand (`x::Int32 ? 1 : 0`); a type with arguments ends with `)`.
            var castTypeAt = -1
            var castTypeEnd = -1
            fun endsOperand(previous: Token?) =
                previous != null && (previous.tokenType in OPERAND_ENDS || previous.end == bindNameEnd || previous.end == castTypeEnd)
            fun leaveTernaries(depth: Int) {
                while (ternaries.isNotEmpty() && ternaries.last().second > depth) {
                    val question = ternaries.removeAt(ternaries.lastIndex).first
                    ambiguous += SyntacticRange(question.start, question.end + 1)
                }
            }
            for ((index, token) in tokens.withIndex()) {
                if (index and 1023 == 1023) checkCanceled()
                if (index <= skipThrough) continue
                if (index == castTypeAt) castTypeEnd = token.end
                val previous = tokens.getOrNull(index - 1)
                when (token.tokenType) {
                    TokenType.SEMICOLON -> {
                        leaveTernaries(-1)
                        open.clear()
                    }
                    // `?::` (QDCOLON) is a `?` followed by a cast; its `?` follows the same rules.
                    TokenType.PLACEHOLDER, TokenType.QDCOLON -> if (token.text == "?" || token.tokenType == TokenType.QDCOLON) {
                        if (ternary && endsOperand(previous)) {
                            ternaries += token to open.depth
                        } else {
                            if (positional) binds += ParameterOccurrence(null, ParamStyle.POSITIONAL, SyntacticRange(token.start, token.start + 1), null)
                            bindNameEnd = token.end
                        }
                        if (token.tokenType == TokenType.QDCOLON) castTypeAt = index + 1
                    }
                    TokenType.DCOLON -> castTypeAt = index + 1
                    TokenType.L_PAREN -> open.push(Bracket.PAREN)
                    TokenType.L_BRACKET -> open.push(when {
                        halfOpenRanges && previous?.tokenType == TokenType.VALUES -> Bracket.RANGE
                        endsOperand(previous) -> Bracket.SUBSCRIPT
                        else -> Bracket.ARRAY
                    })
                    TokenType.L_BRACE -> {
                        val parameter = typeTokens?.let { typedParameter(text, tokens, index, it) }
                        if (parameter != null) {
                            binds += parameter.first
                            skipThrough = parameter.second
                        } else open.push(Bracket.MAP)
                    }
                    TokenType.LT -> if (open.innermost?.kind == Bracket.TYPE || previous?.text?.uppercase() in TYPE_CONSTRUCTORS) {
                        open.push(Bracket.TYPE)
                    }
                    TokenType.GT -> if (open.innermost?.kind == Bracket.TYPE) open.close(Bracket.TYPE)
                    TokenType.R_PAREN -> {
                        open.close(Bracket.PAREN, Bracket.RANGE)
                        leaveTernaries(open.depth)
                    }
                    TokenType.R_BRACKET -> {
                        open.close(Bracket.ARRAY, Bracket.SUBSCRIPT)
                        leaveTernaries(open.depth)
                    }
                    TokenType.R_BRACE -> {
                        open.close(Bracket.MAP)
                        leaveTernaries(open.depth)
                    }
                    TokenType.COMMA -> open.innermost?.takeIf { it.kind == Bracket.MAP }?.colonSeen = false
                    TokenType.COLON -> {
                        val pending = ternaries.lastOrNull()?.takeIf { it.second == open.depth }
                        val next = tokens.getOrNull(index + 1)
                        val startsBind = next != null && next.start == token.end + 1 && text.getOrNull(token.start - 1) != ':' &&
                            PARAMETER_NAME.matchAt(text, next.start) != null
                        if (pending != null && (endsOperand(previous) || previous === pending.first && !startsBind)) {
                            // The ternary's colon; an empty middle (`x ? : y`) is not proven either way.
                            ternaries.removeAt(ternaries.lastIndex)
                            if (previous === pending.first) ambiguous += SyntacticRange(pending.first.start, pending.first.end + 1)
                            continue
                        }
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
            leaveTernaries(-1)
            return binds to ambiguous
        }

        // A fused multiword keyword (`double precision`) binds only its first word.
        private fun boundName(spelled: String): String? {
            val word = PARAMETER_NAME.matchAt(spelled, 0)?.value ?: return null
            return word.takeIf { it.length == spelled.length || spelled[it.length].isWhitespace() }
        }

        private val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val TYPE_ARGUMENT_STOPS = setOf(TokenType.COLON, TokenType.L_BRACE, TokenType.R_BRACE, TokenType.SEMICOLON)
        private val TYPE_CONSTRUCTORS = setOf("STRUCT", "ARRAY", "MAP", "VARIANT")
        private val OPERAND_ENDS = setOf(
            TokenType.VAR, TokenType.IDENTIFIER, TokenType.NUMBER, TokenType.STRING, TokenType.NATIONAL_STRING,
            TokenType.RAW_STRING, TokenType.HEREDOC_STRING, TokenType.BIT_STRING, TokenType.HEX_STRING,
            TokenType.BYTE_STRING, TokenType.UNICODE_STRING, TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE,
            TokenType.END, TokenType.NULL, TokenType.TRUE, TokenType.FALSE,
        )
    }
}
