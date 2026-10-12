package dev.brikk.house.sql.intellij.lexer

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.lexer.LexerPosition
import com.intellij.psi.tree.IElementType
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import org.jetbrains.annotations.ApiStatus

/**
 * Lexes each statement of the [coreDialect]'s script ranges on its own with a base lexer from
 * [baseFactory], so no base token runs past the end of its statement: a raw data tail such as
 * `INSERT ... FORMAT JSONEachRow` data ends where core ends the statement, and a token that
 * swallows the statement's terminating `;` is cut before it, leaving the `;` its own token.
 *
 * Restarts: the base lexer starts afresh in its initial state at every statement start. [getState]
 * is 0 at the first token of a statement and 1 elsewhere, so the editor highlighter restarts lexing
 * and stops relexing only at statement starts, where a restart gives the same tokens; the base
 * lexer's own states, which may be negative, never show. Restoring a position relexes from the start
 * of its statement. Starting at any offset treats it as a statement start.
 *
 * Core ranges are read lazily, a window at a time, so an edit's relexing costs about the size of
 * its statement rather than of the rest of the text.
 */
@ApiStatus.Experimental
public class StatementBoundedSqlLexer(baseFactory: () -> Lexer, private val coreDialect: String) : LexerBase() {
    private val base: Lexer = baseFactory()
    private var buffer: CharSequence = ""
    private var bufferEnd = 0

    /** Statement ranges after the current one, read ahead from core. */
    private val pending = ArrayDeque<Bound>()
    private var bound = Bound(0, NONE)

    /** Statement starts of this pass, in offset order. */
    private val starts = ArrayList<Int>()

    private var type: IElementType? = null
    private var start = 0
    private var end = 0
    private var first = true

    /** Whether the current token was cut before the terminator, where the base lexer resumes. */
    private var cut = false

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        pending.clear()
        starts.clear()
        enterStatement(startOffset)
    }

    override fun getState(): Int = if (first) 0 else 1

    override fun getTokenType(): IElementType? = type

    override fun getTokenStart(): Int = start

    override fun getTokenEnd(): Int = end

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        if (type == null) return
        first = false
        if (cut) {
            cut = false
            base.start(buffer, bound.terminator, bound.end, 0)
        } else {
            base.advance()
        }
        load()
    }

    override fun restore(position: LexerPosition) {
        val offset = position.offset
        val index = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        val from = starts.getOrNull(index) ?: return start(buffer, offset, bufferEnd, 0)
        val keep = starts.subList(0, index).toList()
        start(buffer, from, bufferEnd, 0)
        starts.addAll(0, keep)
        while (type != null && start < offset) advance()
    }

    private fun enterStatement(offset: Int) {
        first = true
        cut = false
        if (offset >= bufferEnd) {
            type = null
            start = bufferEnd
            end = bufferEnd
            return
        }
        bound = pending.removeFirstOrNull() ?: readAhead(offset)
        starts.add(offset)
        base.start(buffer, offset, bound.end, 0)
        load()
    }

    private fun load() {
        val next = base.tokenType ?: return enterStatement(bound.end)
        type = next
        start = base.tokenStart
        end = base.tokenEnd
        if (start < bound.terminator && end > bound.terminator) {
            end = bound.terminator
            cut = true
        }
    }

    /**
     * Reads core ranges from [from] in growing windows until one ends inside the window (or the
     * window reaches the end), queues the other complete ones, and returns the first. A range that
     * ends before its window's end ends at a `;` all of whose preceding tokens lie in the window, so
     * the whole text has the same range.
     */
    private fun readAhead(from: Int): Bound {
        var window = WINDOW
        while (true) {
            val to = if (bufferEnd - from <= window) bufferEnd else from + window
            val ranges = SqlScriptSnapshot.parse(buffer.subSequence(from, to).toString(), coreDialect).statements
                .filter { from + it.end < to || to == bufferEnd }
            if (ranges.isNotEmpty()) {
                ranges.mapTo(pending) { range ->
                    val rangeEnd = from + range.end
                    val terminated = range.boundaryError == null && buffer[rangeEnd - 1] == ';'
                    Bound(rangeEnd, if (terminated) rangeEnd - 1 else NONE)
                }
                return pending.removeFirst()
            }
            window = if (window >= bufferEnd) bufferEnd else window * 2
        }
    }

    /** The end of a statement range of the text and the offset of its terminating `;`, or [NONE]. */
    private class Bound(val end: Int, val terminator: Int)
}

private const val WINDOW = 4096
private const val NONE = -1
