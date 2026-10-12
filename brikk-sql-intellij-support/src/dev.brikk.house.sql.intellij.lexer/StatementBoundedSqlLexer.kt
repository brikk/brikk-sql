package dev.brikk.house.sql.intellij.lexer

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.lexer.LexerPosition
import com.intellij.psi.tree.IElementType
import org.jetbrains.annotations.ApiStatus

/**
 * Lexes each statement of the [coreDialect]'s script ranges on its own with a base lexer from
 * [baseFactory], so no base token runs past the end of its statement: inline `INSERT ... FORMAT`
 * data ends where core frames it, and a token that swallows the statement's terminating `;` is cut
 * before it, the base restarting at the `;` so it reads a separator.
 *
 * Restarts: the base lexer starts afresh in its initial state at every statement start. [getState]
 * is 0 at the first token of a statement and 1 elsewhere, so the editor highlighter restarts lexing
 * and stops relexing only at statement starts, where a restart gives the same tokens; the base
 * lexer's own states, which may be negative, never show. Restoring a position relexes from the start
 * of its statement. Starting in state 0 treats the offset as a statement start.
 *
 * Core ranges are read lazily, a window at a time, so an edit's relexing costs about the size of
 * its statement rather than of the rest of the text; a pass from the start of a text shares them
 * with the boundary gate's parse of the same text.
 */
@ApiStatus.Experimental
public class StatementBoundedSqlLexer(baseFactory: () -> Lexer, private val coreDialect: String) : LexerBase() {
    private val base: Lexer = baseFactory()
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var statements = CoreStatements("", 0, coreDialect)

    /** The end of the current statement's range and the offset of its terminating `;`, if any. */
    private var statementEnd = 0
    private var terminator: Int? = null

    private var type: IElementType? = null
    private var start = 0
    private var end = 0
    private var first = true

    /** Whether the base lexer was restarted at the current statement's terminator. */
    private var atTerminator = false

    /** Whether the current token was cut before the terminator, where the base lexer resumes. */
    private var cut = false

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        statements = if (startOffset == 0) CoreStatements.of(buffer, endOffset, coreDialect)
        else CoreStatements(buffer, endOffset, coreDialect, startOffset)
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
            restartAtTerminator()
        } else {
            base.advance()
        }
        load()
    }

    override fun restore(position: LexerPosition) {
        val offset = position.offset
        val from = statements.rangeAt(offset)?.start ?: return start(buffer, offset, bufferEnd, 0)
        enterStatement(from)
        while (type != null && start < offset) advance()
    }

    private fun enterStatement(offset: Int) {
        first = true
        cut = false
        atTerminator = false
        val range = statements.rangeAt(offset)
        if (range == null) {
            type = null
            start = bufferEnd
            end = bufferEnd
            return
        }
        statementEnd = range.end
        terminator = range.terminator
        base.start(buffer, offset, statementEnd, 0)
        load()
    }

    private fun load() {
        val next = base.tokenType ?: return enterStatement(statementEnd)
        val at = terminator
        if (at != null && base.tokenStart == at && !atTerminator) return restartAtTerminator().also { load() }
        type = next
        start = base.tokenStart
        end = base.tokenEnd
        if (at != null && start < at && end > at) {
            end = at
            cut = true
        }
    }

    /** Lexes the terminator afresh, so the base reads it as the `;` it is, not as part of raw data. */
    private fun restartAtTerminator() {
        val at = checkNotNull(terminator)
        atTerminator = true
        base.start(buffer, at, statementEnd, 0)
    }
}
