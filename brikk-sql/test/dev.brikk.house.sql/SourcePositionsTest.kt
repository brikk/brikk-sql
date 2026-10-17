package dev.brikk.house.sql

import dev.brikk.house.sql.parser.ColumnUnit
import dev.brikk.house.sql.parser.sourceOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourcePositionsTest {
    private val text = "SELECT 1\r\nFROM '\uD83D\uDE00\uD83D\uDE00' x\r\nWHERE"
    private val x = text.indexOf(" x") + 1

    @Test
    fun utf16ColumnsCountCodeUnits() {
        val column = x - text.indexOf("FROM") + 1
        assertEquals(text.indexOf("FROM"), sourceOffset(text, 2, 1))
        assertEquals(x, sourceOffset(text, 2, column))
        assertEquals(x, sourceOffset(text, 2, column - 1, firstColumn = 0))
    }

    @Test
    fun codePointColumnsCountSupplementaryCharactersOnce() {
        val column = x - text.indexOf("FROM") + 1 - 2
        assertEquals(x, sourceOffset(text, 2, column, ColumnUnit.CODE_POINT))
        assertEquals(x, sourceOffset(text, 2, column - 1, ColumnUnit.CODE_POINT, firstColumn = 0))
        assertEquals(text.indexOf("WHERE"), sourceOffset(text, 3, 0, ColumnUnit.CODE_POINT, firstColumn = 0))
    }

    @Test
    fun lineEndsAllowTheEndOfContentButNeverTheCarriageReturnOrTheNextLine() {
        assertEquals(text.indexOf('\r'), sourceOffset(text, 1, 9))
        assertNull(sourceOffset(text, 1, 10))
        val lineTwoEnd = text.lastIndexOf('\r')
        assertEquals(lineTwoEnd, sourceOffset(text, 2, lineTwoEnd - text.indexOf("FROM") - 2 + 1, ColumnUnit.CODE_POINT))
        assertNull(sourceOffset(text, 2, lineTwoEnd - text.indexOf("FROM") - 2 + 2, ColumnUnit.CODE_POINT))
        assertEquals(text.length, sourceOffset(text, 3, 6))
        assertNull(sourceOffset(text, 3, 7))
    }

    @Test
    fun positionsOutsideTheTextAreNull() {
        assertNull(sourceOffset(text, 0, 1))
        assertNull(sourceOffset(text, 4, 1))
        assertNull(sourceOffset(text, 1, 0))
        assertNull(sourceOffset(text, 1, Int.MAX_VALUE, ColumnUnit.CODE_POINT))
        assertNull(sourceOffset(text, Int.MAX_VALUE, 1))
    }
}
