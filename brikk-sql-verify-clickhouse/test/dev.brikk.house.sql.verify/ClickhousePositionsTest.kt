package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The server's syntax errors land on the exact UTF-16 range, whatever the characters and line breaks before them. */
internal class ClickhousePositionsTest {
    private val verifier by lazy { ClickhouseServer.verifier() }

    private fun rejected(sql: String): VerifyError = assertIs<VerifyResult.Rejected>(verifier.verify(sql), sql).error

    @Test
    fun aValidStatementIsAccepted() {
        ClickhouseServer.require()
        assertEquals(VerifyResult.Accepted, verifier.verify("SELECT {p:UInt8}, 'ü😀' FROM numbers(1)"))
    }

    @Test
    fun supplementaryCharactersBeforeTheErrorAreCountedInUtf16() {
        val sql = "SELECT 'ü😀' FRM t"
        val error = rejected(sql)
        val t = sql.lastIndexOf('t')
        assertEquals(t, error.offset, error.toString())
        assertEquals(t + 1, error.endOffset)
        assertEquals(1, error.line)
        assertTrue(error.message.startsWith("Syntax error at t: Expected one of:"), error.message)
    }

    @Test
    fun crlfLinesKeepTheirColumns() {
        val sql = "SELECT 'é'\r\nFROM t\r\nWHERE a = = 1"
        val error = rejected(sql)
        val second = sql.lastIndexOf('=')
        assertEquals(second, error.offset)
        assertEquals(3, error.line)
        assertEquals(second - sql.lastIndexOf('\n'), error.column)
    }

    @Test
    fun anErrorAtTheEndIsAtTheTextsEnd() {
        val sql = "SELECT 1\r\nFROM t WHERE"
        val error = rejected(sql)
        assertEquals(sql.length, error.offset)
        assertEquals(null, error.endOffset)
        assertTrue(error.message.startsWith("Syntax error at the end of the query"), error.message)
    }

    /** The server reads no inline data, so an accepted INSERT carrying some is not checked, wrapped or not. */
    @Test
    fun insertsWithInlineDataAreUnverifiedAndAFormatInAStringIsNotData() {
        ClickhouseServer.require()
        assertIs<VerifyResult.Unverified>(verifier.verify("EXPLAIN AST INSERT INTO t VALUES (1), (2"))
        assertIs<VerifyResult.Unverified>(verifier.verify("INSERT INTO t VALUES (1), (2"))
        assertEquals(VerifyResult.Accepted, verifier.verify("INSERT INTO t SELECT 'x FORMAT y'"))
    }

    @Test
    fun parenthesisErrorsKeepTheirPositionAndMessage() {
        val sql = "SELECT 'é' FROM t WHERE (a = 1)) x"
        val error = rejected(sql)
        assertEquals(sql.lastIndexOf(')'), error.offset)
        assertEquals(sql.lastIndexOf(')') + 1, error.endOffset)
        assertTrue(error.message.startsWith("Syntax error at ): Unmatched parentheses:"), error.message)
        val dotted = rejected("SELECT 1 FRM t. Expected one")
        assertTrue(dotted.message.startsWith("Syntax error at t: Expected one of:"), dotted.message)
    }

    @Test
    fun anExpressionIsPositionedInTheFragment() {
        val error = assertIs<VerifyResult.Rejected>(verifier.verifyExpression("plus(1, , 2)")).error
        assertEquals(8, error.offset)
    }
}
