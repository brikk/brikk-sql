package dev.brikk.house.sql.intellij.lexer

import com.intellij.lexer.Lexer
import com.intellij.lexer.LookAheadLexer
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlLanguage
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.SqlScriptParameters
import org.jetbrains.annotations.ApiStatus

/**
 * A lexer that bridges dialect syntax to the platform grammar of its [base] lexer. A [rule] either
 * passes a token through or turns a span into one token the grammar accepts as a unit: a string in
 * expression position, an identifier in table or type position, or a block comment for a droppable
 * modifier. The same lexer feeds the editor highlighter, so masked spans need recoloring.
 *
 * Statement context ([parenDepth], [lastMeaningful], [statementHead] and the subclass's own, reset
 * in [resetStatement]) starts fresh at the buffer start and after every `;` the base lexer reads
 * outside a consumed span. Whitespace and comments never reach a rule or the context.
 *
 * Restarts: [getState] reports the base state only where the context is fresh, so the editor
 * highlighter restarts lexing at statement starts and stops relexing only at one. Starting
 * elsewhere would lose context; the state makes the highlighter never do so. Restoring a saved
 * position relexes from the start of its statement.
 *
 * States are never negative: the console's parameter preprocessing reserves negative states for
 * its own matches. Bits 0-14 hold the base lexer's state, which must fit them; bit 15 marks a
 * token inside a statement; bits 16-30 are non-zero for a token after the first of a lookahead,
 * as [LookAheadLexer] encodes it, capped so the sign bit stays clear. [start] hands the base only
 * its bits, as [LookAheadLexer] hands its base only the low 16.
 */
@ApiStatus.Experimental
public abstract class MaskingSqlLexer(
    base: Lexer,
    private val castMask: CastTargetMask? = null,
    /**
     * The core dialect whose IDE `:name` binds a span masked as a comment keeps: core's parameter
     * discovery finds them in the span, and each stays a [KEPT_BIND_COLON] and a name token, so a
     * lenient fallback (`StatementBoundaryGate`) makes it a parameter the console prompts for and
     * substitutes. Null masks binds with the rest of the span.
     */
    private val parameterDialect: String? = null,
) : LookAheadLexer(base) {
    /** Open parentheses of passed-through tokens in this statement. */
    protected var parenDepth: Int = 0
        private set

    /** The last noted token: an upper-cased word, a symbol, or the word a span helper noted. */
    protected var lastMeaningful: String? = null
        private set

    /** The first letter-led noted token of this statement. */
    protected var statementHead: String? = null
        private set

    private val castDepths = ArrayDeque<Int>()

    private var fresh = true
    private var restartable = true

    /** The fresh points of [startsBuffer] seen so far, in offset order. */
    private val starts = ArrayList<FreshPoint>()
    private var startsBuffer: CharSequence? = null

    /**
     * Applies the dialect's rules to the base token that [upper] spells in upper case. Returns
     * false to pass it through, or consumes at least that token and returns true.
     */
    protected abstract fun rule(base: Lexer, upper: String): Boolean

    /** Resets the subclass's statement context, at the start and after every `;`. */
    protected open fun resetStatement() {}

    /** Prepares a lexing pass over `[startOffset, endOffset)` of [buffer], before the first token. */
    protected open fun onStart(buffer: CharSequence, startOffset: Int, endOffset: Int) {}

    /** Observes [token] being noted, while [lastMeaningful] still holds the previous one. */
    protected open fun onNote(token: String) {}

    /** Observes a passed-through `(` that opened depth [depth]. */
    protected open fun onOpenParen(depth: Int) {}

    /** Observes a passed-through `)` that closes depth [depth]. */
    protected open fun onCloseParen(depth: Int) {}

    final override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        if (buffer !== startsBuffer) {
            startsBuffer = buffer
            starts.clear()
        }
        resetContext()
        onStart(buffer, startOffset, endOffset)
        super.start(buffer, startOffset, endOffset, initialState and BASE_STATE)
    }

    final override fun getState(): Int {
        val state = super.getState()
        val lookahead = (state ushr 16).coerceAtMost(LOOKAHEAD_CAP) shl 16
        return lookahead or (state and BASE_STATE) or if (restartable) 0 else IN_STATEMENT
    }

    final override fun restore(position: LookAheadLexerPosition) {
        val offset = position.offset
        val index = starts.binarySearch { it.offset.compareTo(offset) }.let { if (it >= 0) it else -it - 2 }
        val from = starts.getOrNull(index) ?: return super.restore(position)
        start(bufferSequence, from.offset, bufferEnd, from.state)
        while (tokenType != null && tokenStart < offset) advance()
    }

    final override fun lookAhead(baseLexer: Lexer) {
        restartable = fresh
        if (fresh && starts.lastOrNull().let { it == null || it.offset < baseLexer.tokenStart }) {
            starts.add(FreshPoint(baseLexer.tokenStart, baseLexer.state))
        }
        val type = baseLexer.tokenType
        val text = baseLexer.tokenText
        when {
            type == null || type == TokenType.WHITE_SPACE || type in SqlTokens.COMMENT_TOKENS || text.isBlank() -> advanceLexer(baseLexer)
            text == ";" -> {
                advanceLexer(baseLexer)
                resetContext()
            }
            else -> {
                fresh = false
                val upper = text.uppercase()
                if (!castRule(baseLexer, upper) && !rule(baseLexer, upper)) emit(baseLexer)
            }
        }
    }

    private fun resetContext() {
        parenDepth = 0
        lastMeaningful = null
        statementHead = null
        castDepths.clear()
        fresh = true
        resetStatement()
    }

    // --- context ---------------------------------------------------------------------------

    /** Records [token] as the last meaningful one; the first letter-led token becomes the head. */
    protected fun note(token: String) {
        if (statementHead == null && token.firstOrNull()?.isLetter() == true) statementHead = token
        onNote(token)
        lastMeaningful = token
    }

    /** Passes the current token through, tracking parentheses and noting it. */
    protected fun emit(base: Lexer) {
        val text = base.tokenText
        track(text)
        note(text.uppercase())
        advanceLexer(base)
    }

    /** Passes the current token through as [type], noting it. */
    protected fun emitAs(base: Lexer, type: IElementType) {
        val text = base.tokenText
        track(text)
        note(text.uppercase())
        advanceAs(base, type)
    }

    private fun track(text: String) {
        when (text) {
            "(" -> onOpenParen(++parenDepth)
            ")" -> {
                onCloseParen(parenDepth)
                parenDepth--
                while (castDepths.isNotEmpty() && castDepths.last() > parenDepth) castDepths.removeLast()
            }
        }
    }

    // --- probes: raw buffer, non-consuming ----------------------------------------------------

    /** The offset of the first non-whitespace character at or after [from]. */
    protected fun offsetAfterWhitespace(base: Lexer, from: Int): Int {
        val buffer = base.bufferSequence
        var offset = from
        while (offset < buffer.length && buffer[offset].isWhitespace()) offset++
        return offset
    }

    /** Whether [expected] starts after the whitespace that follows the current token. */
    protected fun nextIs(base: Lexer, expected: String): Boolean {
        val offset = offsetAfterWhitespace(base, base.tokenEnd)
        return base.bufferSequence.startsWith(expected, offset)
    }

    /** Whether a digit follows the current token and its whitespace. */
    protected fun nextIsDigit(base: Lexer): Boolean =
        base.bufferSequence.getOrNull(offsetAfterWhitespace(base, base.tokenEnd))?.isDigit() == true

    /** Whether [words] follow the current token as whole words, in order, ignoring case and whitespace. */
    protected fun wordsFollow(base: Lexer, vararg words: String): Boolean {
        var offset = base.tokenEnd
        for (word in words) {
            offset = offsetAfterWhitespace(base, offset)
            if (!isWordAt(base.bufferSequence, offset, word)) return false
            offset += word.length
        }
        return true
    }

    /** The word (letters, digits, `_`) at [offset], possibly empty. */
    protected fun bufferWord(base: Lexer, offset: Int): String {
        val buffer = base.bufferSequence
        var end = offset
        while (end < buffer.length && buffer[end].isWordPart()) end++
        return buffer.subSequence(offset, end).toString()
    }

    // --- consumers: advance the base lexer without emitting -----------------------------------

    /** Advances over whitespace tokens. */
    protected fun skipWhitespaceTokens(base: Lexer) {
        while (base.tokenType != null && base.tokenText.isBlank()) base.advance()
    }

    /** Advances from the current token through the [close] that balances it; one token if it is no [open]. */
    protected fun consumeBalanced(base: Lexer, open: String = "(", close: String = ")") {
        var depth = 0
        do {
            when (base.tokenText) {
                open -> depth++
                close -> depth--
            }
            base.advance()
        } while (base.tokenType != null && depth > 0)
    }

    /** Advances past [count] letter-led tokens and everything between them. */
    protected fun consumeWords(base: Lexer, count: Int) {
        var remaining = count
        while (base.tokenType != null && remaining > 0) {
            if (base.tokenText.firstOrNull()?.isLetter() == true) remaining--
            base.advance()
        }
    }

    // --- spans: emit everything consumed in this lookahead as one token ----------------------

    /** Emits the text consumed since the last emitted token as one [type] token, noting [note] if given. */
    protected fun span(base: Lexer, type: IElementType, note: String?) {
        val dialect = parameterDialect
        if (dialect != null && type == SqlTokens.SQL_BLOCK_COMMENT) maskKeepingParameters(base.tokenStart, dialect) else addToken(base.tokenStart, type)
        note?.let(::note)
    }

    /** Masks up to [end] as comments around the `:name` binds core finds in the span, which stay a kept colon and a name. */
    private fun maskKeepingParameters(end: Int, dialect: String) {
        // A lookahead's first token starts where the lexer's current token does.
        var from = if (cacheSize > 0) getCachedOffset(cacheSize - 1) else tokenStart
        val span = bufferSequence.subSequence(from, end).toString()
        val binds = SqlScriptParameters.parse(span, dialect).occurrences.filter { it.style == ParamStyle.NAMED_COLON }
        val offset = from
        for (bind in binds) {
            val nameRange = bind.nameRange ?: continue
            val colon = offset + bind.range.start
            if (colon > from) addToken(colon, SqlTokens.SQL_BLOCK_COMMENT)
            addToken(colon + 1, KEPT_BIND_COLON)
            addToken(offset + nameRange.end, SqlTokens.SQL_IDENT)
            from = offset + nameRange.end
        }
        if (end > from) addToken(end, SqlTokens.SQL_BLOCK_COMMENT)
    }

    /** Masks [count] words as one comment; context never sees them. */
    protected fun maskWords(base: Lexer, count: Int) {
        consumeWords(base, count)
        span(base, SqlTokens.SQL_BLOCK_COMMENT, null)
    }

    /** Collapses the current word and its following balanced `(...)` into one [type] token. */
    protected fun collapseWithGroup(base: Lexer, type: IElementType, note: String?) {
        base.advance()
        skipWhitespaceTokens(base)
        consumeBalanced(base)
        span(base, type, note)
    }

    /** Masks the current word and its following balanced `(...)` as one comment. */
    protected fun maskWithGroup(base: Lexer) {
        collapseWithGroup(base, SqlTokens.SQL_BLOCK_COMMENT, MASKED)
    }

    /** Masks from the current token up to, not including, the `)` that closes the current depth, or a `;`. */
    protected fun maskToCloseParen(base: Lexer) {
        var depth = 0
        base.advance()
        while (base.tokenType != null) {
            when (base.tokenText) {
                "(" -> depth++
                ")" -> if (depth-- == 0) break
                ";" -> break
            }
            base.advance()
        }
        span(base, SqlTokens.SQL_BLOCK_COMMENT, MASKED)
    }

    /**
     * From a routine `BEGIN`, collapses the body through its balancing `END` into one [type] token,
     * so the `;` inside never ends the statement. Blocks: BEGIN, CASE, statement IF, WHILE,
     * statement REPEAT and LOOP; `END <kind>` closes through the innermost block of that kind and
     * a bare `END` closes the innermost block. A body still being typed, unbalanced at the end of
     * the text, ends before its first `;`, so the statements after it keep their own tokens.
     */
    protected fun collapseRoutineBody(base: Lexer, type: IElementType = SqlTokens.SQL_STRING_TOKEN) {
        val blocks = ArrayDeque(listOf("BEGIN"))
        var firstSemicolon = -1
        base.advance()
        while (base.tokenType != null && blocks.isNotEmpty()) {
            when (val word = base.tokenText.uppercase()) {
                ";" -> if (firstSemicolon < 0) firstSemicolon = base.tokenStart
                "BEGIN", "CASE", "WHILE", "LOOP" -> blocks.addLast(word)
                "IF" -> if (ifIsStatement(base)) blocks.addLast(word)
                "REPEAT" -> if (!nextIs(base, "(")) blocks.addLast(word)
                "END" -> {
                    base.advance()
                    skipWhitespaceTokens(base)
                    val kind = base.tokenText.uppercase().takeIf { it in BLOCK_KINDS && it in blocks }
                    if (kind == null) {
                        blocks.removeLast()
                        continue
                    }
                    while (blocks.removeLast() != kind) Unit
                }
            }
            base.advance()
        }
        if (blocks.isNotEmpty() && firstSemicolon >= 0) base.start(base.bufferSequence, firstSemicolon, base.bufferEnd, 0)
        span(base, type, ROUTINE_BODY)
    }

    /** Whether the `IF` at the current token opens a statement (condition, then THEN) rather than a call. */
    private fun ifIsStatement(base: Lexer): Boolean {
        val buffer = base.bufferSequence
        var offset = offsetAfterWhitespace(base, base.tokenEnd)
        if (offset >= buffer.length) return false
        if (buffer[offset] != '(') return true
        var depth = 0
        var quoted = false
        while (offset < buffer.length) {
            val c = buffer[offset++]
            when {
                quoted -> if (c == '\'') quoted = false
                c == '\'' -> quoted = true
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) break
            }
        }
        return isWordAt(buffer, offsetAfterWhitespace(base, offset), "THEN")
    }

    private fun castRule(base: Lexer, upper: String): Boolean {
        val mask = castMask ?: return false
        if (upper in mask.functions && nextIs(base, "(")) {
            castDepths.addLast(parenDepth + 1)
            emit(base)
            return true
        }
        if (upper != "AS" || castDepths.lastOrNull() != parenDepth) return false
        castDepths.removeLast()
        val target = nextSignificant(base)
        if (target == null || target in CAST_DELIMITERS || target in mask.keptTargets) return false
        when (mask.extent) {
            CastTargetMask.Extent.ToCloseParen -> maskToCloseParen(base)
            CastTargetMask.Extent.TargetType -> maskCastType(base)
        }
        return true
    }

    /** The upper-cased first token after the current one that is no whitespace or comment, or null at the end. Never consumes. */
    private fun nextSignificant(base: Lexer): String? {
        val position = base.currentPosition
        base.advance()
        skipTrivia(base)
        val text = base.tokenType?.let { base.tokenText.uppercase() }
        base.restore(position)
        return text
    }

    private fun skipTrivia(base: Lexer) {
        while (base.tokenType != null && (base.tokenText.isBlank() || base.tokenType in SqlTokens.COMMENT_TOKENS)) base.advance()
    }

    private fun maskCastType(base: Lexer) {
        base.advance()
        skipTrivia(base)
        if (base.tokenType != null) base.advance()
        consumeBalancedIfNext(base, '<', '>')
        consumeBalancedIfNext(base, '(', ')')
        span(base, SqlTokens.SQL_BLOCK_COMMENT, MASKED)
    }

    /**
     * Counts characters, because the base lexer may join brackets into one token such as `>>`.
     * Stops before a `;`, and before a `)` that cannot belong to the group.
     */
    private fun consumeBalancedIfNext(base: Lexer, open: Char, close: Char) {
        val at = offsetAfterWhitespace(base, base.tokenStart)
        if (base.bufferSequence.getOrNull(at) != open) return
        var depth = 0
        // Parentheses inside angle brackets (`ARRAY<VARCHAR(120)>`) nest; an unmatched one ends the scan.
        var parentheses = 0
        while (base.tokenType != null) {
            val text = base.tokenText
            if (text == ";" || text == ")" && open != '(' && parentheses == 0) break
            for (c in text) {
                when {
                    c == open -> depth++
                    c == close -> depth--
                    open != '(' && c == '(' -> parentheses++
                    open != '(' && c == ')' -> parentheses--
                }
            }
            val end = base.tokenEnd
            base.advance()
            if (depth <= 0 && end > at) break
        }
    }

    protected companion object {
        /** The word [maskWithGroup], [maskToCloseParen] and cast masks note. */
        public const val MASKED: String = "MASKED"

        /** The word [collapseRoutineBody] notes. */
        public const val ROUTINE_BODY: String = "ROUTINE_BODY"
    }
}

private const val BASE_STATE = 0x7FFF
private const val IN_STATEMENT = 0x8000
private const val LOOKAHEAD_CAP = 0x7FFF
private val BLOCK_KINDS = setOf("IF", "WHILE", "REPEAT", "LOOP", "CASE")
private val CAST_DELIMITERS = setOf(";", ")", ",")

/** An offset where the context is fresh, with the base lexer's state there. */
private class FreshPoint(val offset: Int, val state: Int)

/**
 * Masks the target of a cast call written `fn(value AS target)` as a comment, so the call parses
 * as a one-argument function. Any target is masked unless, after whitespace and comments, `AS` is
 * followed by `;`, `)`, `,` or the end of the text: input still being typed keeps those tokens.
 */
@ApiStatus.Experimental
public data class CastTargetMask(
    /** Upper-case function names, each followed by `(`. */
    val functions: Set<String> = setOf("TRY_CAST"),
    /** Upper-case first target words the grammar accepts; their casts stay unmasked. */
    val keptTargets: Set<String> = emptySet(),
    val extent: Extent = Extent.ToCloseParen,
) {
    public enum class Extent {
        /** From `AS` up to the call's closing parenthesis. */
        ToCloseParen,

        /** `AS`, the target word or quoted name, then a balanced `<...>` and `(...)` if they follow. */
        TargetType,
    }
}

private fun Char.isWordPart(): Boolean = isLetterOrDigit() || this == '_'

/** The `:` of an IDE bind kept in a masked span (`MaskingSqlLexer`'s `parameterDialect`). */
@ApiStatus.Experimental
public val KEPT_BIND_COLON: IElementType = IElementType("KEPT_BIND_COLON", SqlLanguage.INSTANCE)


private fun isWordAt(buffer: CharSequence, offset: Int, word: String): Boolean =
    offset + word.length <= buffer.length &&
        buffer.regionMatches(offset, word, 0, word.length, ignoreCase = true) &&
        buffer.getOrNull(offset + word.length)?.isWordPart() != true
