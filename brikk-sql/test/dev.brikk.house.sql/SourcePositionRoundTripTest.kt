package dev.brikk.house.sql

import dev.brikk.house.sql.parser.ColumnUnit
import dev.brikk.house.sql.parser.SourcePosition
import dev.brikk.house.sql.parser.sourceOffset
import dev.brikk.house.sql.parser.sourcePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourcePositionRoundTripTest {
    private val text = "SELECT 1\r\nFROM '\uD83D\uDE00\uD83D\uDE00' x\nWHERE"

    @Test
    fun everyOffsetReadsBackInBothUnits() {
        for (unit in ColumnUnit.entries) for (offset in 0..text.length) {
            val insidePair = offset in 1 until text.length && text[offset].isLowSurrogate()
            val betweenCrLf = offset > 0 && text[offset - 1] == '\r'
            if (insidePair && unit == ColumnUnit.CODE_POINT || betweenCrLf) continue
            val position = sourcePosition(text, offset, unit)
            assertEquals(offset, sourceOffset(text, position.line, position.column, unit), "$unit $offset -> $position")
        }
    }

    @Test
    fun columnsCountTheUnitFromTheGivenOrigin() {
        val x = text.indexOf(" x") + 1
        assertEquals(SourcePosition(2, 13), sourcePosition(text, x))
        assertEquals(SourcePosition(2, 10), sourcePosition(text, x, ColumnUnit.CODE_POINT, firstColumn = 0))
        assertEquals(SourcePosition(3, 0), sourcePosition(text, text.indexOf("WHERE"), firstColumn = 0))
    }

    @Test
    fun anOffsetInsideASurrogatePairIsItsCodePointsStart() {
        val pair = text.indexOf('\uD83D')
        assertEquals(sourcePosition(text, pair, ColumnUnit.CODE_POINT), sourcePosition(text, pair + 1, ColumnUnit.CODE_POINT))
    }

    @Test
    fun offsetsOutsideTheTextAreRejected() {
        assertFailsWith<IllegalArgumentException> { sourcePosition(text, -1) }
        assertFailsWith<IllegalArgumentException> { sourcePosition(text, text.length + 1) }
    }
}
