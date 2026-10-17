package dev.brikk.house.sql.verify

import dev.brikk.house.sql.parser.ColumnUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

internal class VerifyResultTest {
    @Test
    fun codePointColumnsBecomeUtf16RangesAcrossCrlfLines() {
        val text = "SELECT '\uD83D\uDE00'\r\n  , '\uD83D\uDE80' FROM WHERE"
        val where = text.indexOf("WHERE")
        val lineStart = text.indexOf('\n') + 1
        val codePointColumn = text.codePointCount(lineStart, where)

        assertEquals(
            VerifyError("bad", line = 2, column = where - lineStart + 1, offset = where),
            VerifyError.at(text, "bad", 2, codePointColumn, ColumnUnit.CODE_POINT, firstColumn = 0),
        )
        assertEquals(
            VerifyError("bad", line = 1, column = 8, offset = 7),
            VerifyError.at(text, "bad", 1, 8),
        )
    }

    @Test
    fun utf8ByteOffsetsBecomeUtf16RangesAcrossCrlfLines() {
        // ü is 2 UTF-8 bytes and 1 UTF-16 unit; 😀 is 4 bytes and 2 units.
        val text = "SELECT 'ü😀'\r\nFRM t"
        val t = text.indexOf(" t") + 1
        val byte = text.substring(0, t).toByteArray(Charsets.UTF_8).size
        assertEquals(
            VerifyError("bad", line = 2, column = 5, offset = t, endOffset = t + 1),
            VerifyError.atUtf8Byte(text, "bad", byte, byte + 1),
        )
        val end = text.toByteArray(Charsets.UTF_8).size
        assertEquals(VerifyError("bad", line = 2, column = 6, offset = text.length), VerifyError.atUtf8Byte(text, "bad", end))
    }

    @Test
    fun utf8ByteOffsetsInsideACharacterOrOutsideTheTextAreDropped() {
        val text = "SELECT 'ü'"
        assertEquals(VerifyError("bad"), VerifyError.atUtf8Byte(text, "bad", 9))
        assertEquals(VerifyError("bad"), VerifyError.atUtf8Byte(text, "bad", 12))
        assertEquals(VerifyError("bad"), VerifyError.atUtf8Byte(text, "bad", -1))
        assertEquals(VerifyError("bad", line = 1, column = 8, offset = 7), VerifyError.atUtf8Byte(text, "bad", 7, endByte = 9))
    }

    @Test
    fun positionsOutsideTheTextAreDropped() {
        assertEquals(VerifyError("bad"), VerifyError.at("SELECT 1", "bad", 2, 1))
        assertEquals(VerifyError("bad"), VerifyError.at("SELECT 1", "bad", 1, 20))
        assertEquals(VerifyError("bad"), VerifyError.atOffset("SELECT 1", "bad", 9))
        assertEquals(
            VerifyError("bad", line = 1, column = 9, offset = 8),
            VerifyError.atOffset("SELECT 1", "bad", 8, endOffset = 12),
        )
    }

    @Test
    fun offsetsCarryTheirLineAndColumn() {
        val text = "SELECT\r\n  x FROM"
        assertEquals(
            VerifyError("bad", line = 2, column = 3, offset = 10, endOffset = 11),
            VerifyError.atOffset(text, "bad", 10, endOffset = 11),
        )
        assertEquals(VerifyError("bad", line = 1, column = 7, offset = 6), VerifyError.atOffset(text, "bad", 6))
    }

    @Test
    fun codePointOffsetsBecomeUtf16Ranges() {
        val text = "SELECT '\uD83D\uDE00'\r\nFROM WHERE"
        val where = text.indexOf("WHERE")
        val codePoint = text.codePointCount(0, where)
        assertEquals(
            VerifyError("bad", line = 2, column = 6, offset = where, endOffset = where + 5),
            VerifyError.atCodePoint(text, "bad", codePoint, codePoint + 5),
        )
        assertEquals(VerifyError("bad"), VerifyError.atCodePoint(text, "bad", text.length))
        assertEquals(VerifyError("bad", line = 1, column = 1, offset = 0), VerifyError.atCodePoint(text, "bad", 0, 0))
    }

    @Test
    fun prefixRemovalMovesFirstLinePositionsOnly() {
        val wrapped = VerifyResult.Rejected(listOf(
            VerifyError("first", line = 1, column = 12, offset = 11, endOffset = 13),
            VerifyError("second", line = 2, column = 4, offset = 25),
            VerifyError("inside prefix", line = 1, column = 3, offset = 2, endOffset = 4),
            VerifyError("unpositioned"),
        ))
        assertEquals(
            VerifyResult.Rejected(listOf(
                VerifyError("first", line = 1, column = 5, offset = 4, endOffset = 6),
                VerifyError("second", line = 2, column = 4, offset = 18),
                VerifyError("inside prefix", line = 1, column = 1, offset = 0, endOffset = 0),
                VerifyError("unpositioned"),
            )),
            wrapped.withoutPrefix("SELECT "),
        )
        assertSame(VerifyResult.Accepted, VerifyResult.Accepted.withoutPrefix("SELECT "))
    }

    @Test
    fun rejectionsNeedAnError() {
        assertFailsWith<IllegalArgumentException> { VerifyResult.Rejected(emptyList()) }
        assertEquals(VerifyError("bad"), VerifyResult.Rejected(VerifyError("bad")).error)
    }

    @Test
    fun unavailableVerifiersReportTheirReason() {
        val verifier = UnavailableVerifier("doris", "no parser")
        assertEquals(VerifyResult.Unavailable("no parser"), verifier.verify("SELECT 1"))
        assertEquals(VerifyResult.Unavailable("no parser"), verifier.verifyExpression("1"))
        assertEquals(false, verifier.advisory)
    }
}
