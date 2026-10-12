package dev.brikk.house.sql.verify

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

internal class DuckdbVerifierTest {
    @Test
    fun infrastructureFailuresAreUnavailableNotAccepted() {
        assertIs<VerifyResult.Unavailable>(DuckdbVerifier { throw SQLException("Connection was closed") }.verify("SELECT 1"))

        for (prefix in listOf("Binder Error", "Catalog Error", "Not implemented Error")) {
            assertIs<VerifyResult.Accepted>(classifyDuckdbPrepareFailure("$prefix: detail"))
        }
        val parser = assertIs<VerifyResult.Rejected>(classifyDuckdbPrepareFailure("Parser Error: bad\nLINE 2: 123 invalid"))
        assertEquals(VerifyError("Parser Error: bad\nLINE 2: 123 invalid", line = 2), parser.error)
        assertIs<VerifyResult.Unverified>(classifyDuckdbPrepareFailure("IO Error: disk unavailable"))
    }

    @Test
    fun cancellationPropagatesFromBothChannels() {
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, assertFailsWith<CancellationException> { DuckdbVerifier { throw cancelled }.verify("SELECT 1") })

        val real = DriverManager.getConnection("jdbc:duckdb:")
        val cancellingPrepare = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") throw cancelled
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
        DuckdbVerifier { cancellingPrepare }.use { verifier ->
            assertSame(cancelled, assertFailsWith<CancellationException> { verifier.verify("CREATE TABLE t (a INT)") })
        }
    }

    @Test
    fun codePointPositionsBecomeUtf16RangesAcrossCrlf() {
        DuckdbVerifier().use { verifier ->
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
    }

    @Test
    fun nonSelectStatementsFallBackToPrepare() {
        DuckdbVerifier().use { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("CREATE TABLE t (a INT)"))
            assertIs<VerifyResult.Rejected>(verifier.verify("CREATE TABLE t (a INT"))
            assertIs<VerifyResult.Accepted>(verifier.verifyExpression("x -> '$.family'"))
        }
    }
}
