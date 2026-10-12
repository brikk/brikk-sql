package dev.brikk.house.sql.intellij.lexer

import com.intellij.openapi.progress.ProgressManager
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.SqlStatementRange

/**
 * The [dialect]'s statement ranges of `[origin, end)` of [text], with absolute offsets, read lazily
 * a window at a time: a lookup reads only up to the statement it asks for. A range that ends
 * before its window's end ends at a `;` all of whose preceding tokens lie in the window, so the
 * whole text has the same range; core starts every statement afresh, so ranges read from a
 * statement start are those of the whole text. Safe for concurrent use.
 */
internal class CoreStatements(private val text: CharSequence, private val end: Int, private val dialect: String, val origin: Int = 0) {
    private val ranges = ArrayList<SqlStatementRange>()
    private var readTo = origin

    /** The range holding [offset], which must lie in `[origin, end)`, or null outside them. */
    @Synchronized
    fun rangeAt(offset: Int): SqlStatementRange? {
        if (offset < origin || offset >= end) return null
        while (readTo <= offset) readWindow()
        val index = ranges.binarySearch { if (it.end <= offset) -1 else if (it.start > offset) 1 else 0 }
        return ranges.getOrNull(index)
    }

    private fun readWindow() {
        var window = WINDOW
        while (true) {
            val to = if (end - readTo <= window) end else readTo + window
            val from = readTo
            val complete = SqlScriptSnapshot.parse(text.subSequence(from, to).toString(), dialect, ProgressManager::checkCanceled).statements
                .filter { from + it.end < to || to == end }
            if (complete.isNotEmpty()) {
                complete.mapTo(ranges) { it.copy(start = from + it.start, end = from + it.end, terminator = it.terminator?.let { t -> from + t }) }
                readTo = ranges.last().end
                check(readTo > from) { "Core read no statement from $from" }
                return
            }
            check(to < end) { "Core read no statement in [$from, $end)" }
            window = if (window >= end) end else window * 2
        }
    }

    private fun matches(text: CharSequence, end: Int, dialect: String): Boolean =
        dialect == this.dialect && end == this.end && origin == 0 &&
            (text === this.text || text.length == this.text.length && text.contentEquals(this.text))

    companion object {
        private val recent = ArrayDeque<CoreStatements>()

        /**
         * The ranges of `[0, end)` of [text], shared by the lexer and parser passes over the same
         * text (by content), so a parse reads core once.
         */
        @Synchronized
        fun of(text: CharSequence, end: Int, dialect: String): CoreStatements {
            val found = recent.firstOrNull { it.matches(text, end, dialect) }
            if (found != null) {
                recent.remove(found)
                recent.addFirst(found)
                return found
            }
            return CoreStatements(text, end, dialect).also {
                recent.addFirst(it)
                while (recent.size > SHARED) recent.removeLast()
            }
        }
    }
}

private const val WINDOW = 4096
private const val SHARED = 2
