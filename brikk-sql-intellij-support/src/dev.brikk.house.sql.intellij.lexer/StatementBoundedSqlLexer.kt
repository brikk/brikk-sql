package dev.brikk.house.sql.intellij.lexer

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.lexer.LexerPosition
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.parser.SqlStatementRange
import org.jetbrains.annotations.ApiStatus

/**
 * Lexes each statement of the [coreDialect]'s script ranges on its own with a base lexer from
 * [baseFactory], so no base token runs past the end of its statement: a raw data token of inline
 * `INSERT ... FORMAT` data covers exactly core's data, and a token that swallows the statement's
 * terminating `;` is cut before it, the base restarting at the `;` so it reads a separator.
 *
 * Restarts: the base lexer starts afresh in its initial state at every statement start. [getState]
 * is 0 at the first token of a statement and 1 elsewhere, so the editor highlighter restarts lexing
 * and stops relexing only at statement starts, where a restart gives the same tokens; the base
 * lexer's own states, which may be negative, never show. Restoring a position relexes from the start
 * of its statement. Starting in state 0 treats the offset as a statement start; starting in another
 * state continues the statement holding the offset, within its bounds.
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

    /** The end of the current statement's range, the offset of its terminating `;` and the end of its data, if any. */
    private var statementEnd = 0
    private var terminator: Int? = null
    private var dataEnd: Int? = null

    private var type: IElementType? = null
    private var start = 0
    private var end = 0
    private var first = true

    /** Whether the base lexer was restarted at the current statement's terminator. */
    private var atTerminator = false

    /** Where the base lexer restarts after the current token, which was cut or stretched to core's framing. */
    private var resume: Int? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        val inside = initialState != 0 && startOffset > 0
        statements = if (startOffset == 0 || inside) CoreStatements.of(buffer, endOffset, coreDialect)
        else CoreStatements(buffer, endOffset, coreDialect, startOffset)
        if (inside) continueStatement(startOffset) else enterStatement(startOffset)
    }

    /**
     * Continues the statement holding [offset], as the console's parameter preprocessing asks when
     * it restarts mid-statement in the state reported there: the base lexer restarts at [offset] in
     * its initial state (so a restart mid-token lexes the rest afresh), bounded by the statement.
     */
    private fun continueStatement(offset: Int) {
        val range = statements.rangeAt(offset) ?: return enterStatement(offset)
        first = false
        enter(range)
        base.start(buffer, offset, statementEnd, 0)
        load()
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
        val at = resume
        if (at != null) {
            resume = null
            restartAt(at)
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
        val range = statements.rangeAt(offset)
        if (range == null) {
            type = null
            start = bufferEnd
            end = bufferEnd
            return
        }
        enter(range)
        base.start(buffer, offset, statementEnd, 0)
        load()
    }

    private fun enter(range: SqlStatementRange) {
        statementEnd = range.end
        terminator = range.terminator
        dataEnd = range.dataEnd
        atTerminator = false
        resume = null
    }

    /**
     * Takes the base token, framed as core frames the statement: a raw data token ends where core's
     * data does (stretched over what the base read as SQL, or cut), and no token covers the
     * terminator, which the base lexes afresh so it reads the `;` it is.
     */
    private fun load() {
        val next = base.tokenType ?: return enterStatement(statementEnd)
        val at = terminator
        if (at != null && base.tokenStart == at && !atTerminator) return restartAt(at).also { load() }
        type = next
        start = base.tokenStart
        end = base.tokenEnd
        val data = dataEnd
        if (next == SqlTokens.SQL_RAW_INPUT && data != null && start < data && end != data) {
            end = data
            resume = data
        }
        if (at != null && start < at && end > at) {
            end = at
            resume = at
        }
    }

    private fun restartAt(offset: Int) {
        if (offset == terminator) atTerminator = true
        base.start(buffer, offset, statementEnd, 0)
    }
}
