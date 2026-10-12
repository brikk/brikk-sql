package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal class TrinoVerifierTest {
    private val verifier = TrinoVerifier()

    @Test
    fun acceptsStatementsAndExpressions() {
        assertIs<VerifyResult.Accepted>(verifier.verify("SELECT a FROM t WHERE b = 1"))
        assertIs<VerifyResult.Accepted>(verifier.verifyExpression("JSON_QUERY(x, 'strict $.a' WITH ARRAY WRAPPER)"))
        assertIs<VerifyResult.Rejected>(verifier.verify("JSON_QUERY(x, 'strict $.a' WITH ARRAY WRAPPER)"))
        assertEquals("trino", verifier.engine)
    }

    @Test
    fun codePointColumnsBecomeUtf16RangesAcrossCrlf() {
        for (payload in listOf("ascii", "\uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE80")) {
            for (separator in listOf(" ", "\n", "\r\n")) {
                val sql = "SELECT '$payload',$separator'$payload' FROM WHERE"
                val where = sql.indexOf("WHERE")
                val error = assertIs<VerifyResult.Rejected>(verifier.verify(sql), sql).error
                assertEquals(
                    listOf(sql.take(where).count { it == '\n' } + 1, where - (sql.lastIndexOf('\n', where - 1) + 1) + 1, where),
                    listOf(error.line, error.column, error.offset),
                    "$sql: ${error.message}",
                )
            }
        }
    }

    @Test
    fun expressionErrorsIndexTheFragment() {
        val error = assertIs<VerifyResult.Rejected>(verifier.verifyExpression("'\uD83D\uDE00' +")).error
        assertEquals(listOf(1, 7, 6), listOf(error.line, error.column, error.offset))
    }
}
