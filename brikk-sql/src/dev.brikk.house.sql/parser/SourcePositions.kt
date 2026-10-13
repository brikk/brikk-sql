package dev.brikk.house.sql.parser

import kotlinx.serialization.Serializable

/** How a native parser or server counts columns within a line. */
enum class ColumnUnit { UTF16, CODE_POINT }

/**
 * brikk-native: the UTF-16 offset in [text] of a native engine's 1-based [line] and [column],
 * for errors that do not come from brikk-sql (whose [ParseErrorInfo.start] needs no conversion).
 * [firstColumn] is the engine's column origin, e.g. 0 for Doris `(line N, pos M)`.
 *
 * Lines break at LF, so CRLF text maps exactly. The position just after a line's content is
 * allowed; a position outside [text] or past the end of its line is null, never clamped.
 */
fun sourceOffset(
    text: String,
    line: Int,
    column: Int,
    unit: ColumnUnit = ColumnUnit.UTF16,
    firstColumn: Int = 1,
): Int? {
    if (line < 1) return null
    var lineStart = 0
    repeat(line - 1) {
        lineStart = text.indexOf('\n', lineStart).takeIf { it >= 0 }?.plus(1) ?: return null
    }
    val lineEnd = text.indexOf('\n', lineStart).let { lf ->
        when {
            lf < 0 -> text.length
            lf > lineStart && text[lf - 1] == '\r' -> lf - 1
            else -> lf
        }
    }
    val relative = column - firstColumn
    if (relative < 0) return null
    return when (unit) {
        ColumnUnit.UTF16 -> relative.takeIf { it <= lineEnd - lineStart }?.let { lineStart + it }
        ColumnUnit.CODE_POINT -> {
            var index = lineStart
            repeat(relative) {
                if (index >= lineEnd) return null
                val pair = text[index].isHighSurrogate() && index + 1 < lineEnd && text[index + 1].isLowSurrogate()
                index += if (pair) 2 else 1
            }
            index
        }
    }
}

/** A 1-based line and a column counted from the line's first column. */
@Serializable
data class SourcePosition(val line: Int, val column: Int)

/**
 * brikk-native: the line and column of the UTF-16 [offset] in [text], the inverse of
 * [sourceOffset]: an LSP position is `sourcePosition(text, offset, firstColumn = 0)` with the
 * line made 0-based. Lines break at LF; an offset inside a surrogate pair counts as its code
 * point's start in [ColumnUnit.CODE_POINT].
 */
fun sourcePosition(text: String, offset: Int, unit: ColumnUnit = ColumnUnit.UTF16, firstColumn: Int = 1): SourcePosition {
    require(offset in 0..text.length) { "Offset $offset is outside the text" }
    var line = 1
    var lineStart = 0
    for (index in 0 until offset) if (text[index] == '\n') {
        line++
        lineStart = index + 1
    }
    val column = when (unit) {
        ColumnUnit.UTF16 -> offset - lineStart
        ColumnUnit.CODE_POINT -> {
            var count = 0
            var index = lineStart
            while (index < offset) {
                val width = if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
                if (index + width > offset) break
                index += width
                count++
            }
            count
        }
    }
    return SourcePosition(line, firstColumn + column)
}
