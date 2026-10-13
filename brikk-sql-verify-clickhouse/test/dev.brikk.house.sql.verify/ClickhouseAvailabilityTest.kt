package dev.brikk.house.sql.verify

import com.clickhouse.jdbc.ClickHouseDriver
import java.net.ServerSocket
import java.net.Socket
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Without a usable server there is no verdict, never "clean"; cancellation stops a hanging server's call. */
internal class ClickhouseAvailabilityTest {
    private fun driverConnection(port: Int) = JdbcClickhouseQueryConnection(
        ClickHouseDriver().connect("jdbc:clickhouse://127.0.0.1:$port/default", Properties().apply {
            setProperty("user", "default")
            setProperty("password", "")
            setProperty("socket_timeout", "60000")
        }) ?: error("no connection"),
    )

    @Test
    fun noDataSourceIsUnavailable() {
        assertIs<VerifyResult.Unavailable>(ParseOnlyClickhouseVerifier({ null }).verify("SELECT 1"))
    }

    @Test
    fun aConnectionThatCannotOpenIsUnavailable() {
        val result = ParseOnlyClickhouseVerifier({ throw SQLException("Connection refused") }).verify("SELECT 1")
        assertEquals(VerifyResult.Unavailable("ClickHouse verification is unavailable: the connection failed (Connection refused)."), result)
    }

    @Test
    fun aServerThatIsNotListeningIsUnavailable() {
        val port = ServerSocket(0).use { it.localPort }
        assertIs<VerifyResult.Unavailable>(ParseOnlyClickhouseVerifier({ driverConnection(port) }).verify("SELECT 1"))
    }

    @Test
    fun cancellingStopsAHangingServer() {
        ServerSocket(0).use { server ->
            val accepted = CopyOnWriteArrayList<Socket>()
            thread(isDaemon = true) { runCatching { while (true) accepted += server.accept() } }
            val polls = AtomicInteger()
            val verifier = ParseOnlyClickhouseVerifier({ driverConnection(server.localPort) }, checkCanceled = {
                if (polls.incrementAndGet() > 10) throw CancellationException("edited")
            })
            val started = System.nanoTime()
            assertFailsWith<CancellationException> { verifier.verify("SELECT 1") }
            assertTrue(System.nanoTime() - started < 5_000_000_000L, "cancellation took too long")
            accepted.forEach(Socket::close)
        }
    }

    /** A connection that opens after the call was canceled is closed at once and runs nothing. */
    @Test
    fun aConnectionOpenedAfterCancellingRunsNothing() {
        val opening = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val queries = AtomicInteger()
        val late = object : ClickhouseQueryConnection {
            override fun queryString(sql: String, timeoutSeconds: Int): String? = "SELECT 1".also { queries.incrementAndGet() }
            override fun cancel() = Unit
            override fun close() = closed.countDown()
        }
        val verifier = ParseOnlyClickhouseVerifier({
            opening.countDown()
            // A driver that does not notice the interrupt while it connects.
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                }
            }
            late
        }, checkCanceled = { if (opening.count == 0L) throw CancellationException("edited") })
        assertFailsWith<CancellationException> { verifier.verify("SELECT 1") }
        release.countDown()
        assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS), "the late connection was not closed")
        Thread.sleep(200)
        assertEquals(0, queries.get(), "the late connection ran a query")
    }

    @Test
    fun anInterruptedCallerCancels() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { runCatching { while (true) server.accept() } }
            val verifier = ParseOnlyClickhouseVerifier({ driverConnection(server.localPort) })
            var failure: Throwable? = null
            var interrupted = false
            val caller = thread {
                failure = runCatching { verifier.verify("SELECT 1") }.exceptionOrNull()
                interrupted = Thread.currentThread().isInterrupted
            }
            Thread.sleep(300)
            caller.interrupt()
            caller.join(5_000)
            assertFalse(caller.isAlive)
            assertIs<CancellationException>(failure)
            assertTrue(interrupted)
        }
    }

    @Test
    fun aSyntaxErrorMessageBecomesAPositionedError() {
        val sql = "SELECT 'ü' FRM t"
        val detail = "Syntax error: failed at position 17 (t) (line 1, col 17): t. Expected one of: token, Comma, FROM"
        assertEquals(
            VerifyError("Syntax error at t: Expected one of: token, Comma, FROM", line = 1, column = 16, offset = 15, endOffset = 16),
            syntaxError(sql, detail),
        )
        assertEquals(VerifyError("Syntax error without a position"), syntaxError(sql, "Syntax error without a position"))
    }

    /** The server's echo of the rest of the query ends where the statement says, whatever the token or text holds. */
    @Test
    fun theMessageAfterTheEchoIsKept() {
        val unmatched = syntaxError(
            "SELECT 1 FROM t WHERE (a = 1)) x",
            "Syntax error: failed at position 30 ()): ) x. Unmatched parentheses: ')' at position 30 has no matching opening bracket",
        )
        assertEquals(VerifyError("Syntax error at ): Unmatched parentheses: ')' at position 30 has no matching opening bracket", 1, 30, 29, 30), unmatched)
        val dotted = syntaxError("SELECT 1 FRM t. Expected one", "Syntax error: failed at position 14 (t): t. Expected one. Expected one of: token, Comma")
        assertEquals("Syntax error at t: Expected one of: token, Comma", dotted.message)
        val long = "SELECT 1 FRM t WHERE " + (0 until 60).joinToString(" AND ") { "col$it = $it" }
        val cut = syntaxError(long, "Syntax error: failed at position 14 (t): ${long.substring(13, 113)}... Expected one of: token")
        assertEquals(VerifyError("Syntax error at t: Expected one of: token", 1, 14, 13, 14), cut)
        val multiline = syntaxError("SELECT 1 FRM t\nWHERE a = 1. 2", "Syntax error: failed at position 14 (t) (line 1, col 14): t\nWHERE a = 1. 2. Expected one of: token")
        assertEquals("Syntax error at t: Expected one of: token", multiline.message)
        val end = syntaxError("SELECT (1", "Syntax error: failed at position 10 (end of query): (1. Unmatched parentheses: '(' at position 8 is never closed")
        assertEquals(VerifyError("Syntax error at the end of the query: Unmatched parentheses: '(' at position 8 is never closed", 1, 10, 9), end)
    }

    /** The 0.9.8 driver's compressed transport turns the server's message into this: no verdict. */
    @Test
    fun anUnreadableServerMessageIsNoVerdict() {
        val unreadable = SQLException("Code: 62. DB::Exception: <Unreadable error message> (transport error: 400) (queryId= 8ca1)")
        val result = ParseOnlyClickhouseVerifier({ FailingConnection(unreadable) }).verify("SELECT 1; S")
        assertIs<VerifyResult.Unavailable>(result)
        assertTrue("unreadable" in result.reason, result.reason)
    }

    /** Past the size limit, in the verifier's own query or inside the statement, there is no verdict on the statement. */
    @Test
    fun sizeErrorsAreUnverified() {
        val inside = "Code: 62. DB::Exception: Max query size exceeded (can be increased with the `max_query_size` setting): " +
            "Syntax error: failed at position 201 (+): +. : In scope SELECT formatQuery(unhex('5345')). (SYNTAX_ERROR)"
        val outer = "Code: 62. DB::Exception: Max query size exceeded (can be increased with the `max_query_size` setting): " +
            "Syntax error: failed at position 26 ('5345'): '5345'. (SYNTAX_ERROR)"
        for (message in listOf(inside, outer)) {
            assertIs<VerifyResult.Unverified>(ParseOnlyClickhouseVerifier({ FailingConnection(SQLException(message)) }).verify("SELECT 1"), message)
        }
    }

    /** The old analyzer (before 24.3 by default, selectable through 26.8) reports the same error in another scope. */
    @Test
    fun theOldAnalyzersShapeIsARejectionWithoutTheHex() {
        val old = "Code: 62. DB::Exception: Syntax error: failed at position 14 (t): t. Expected one of: token, Comma: " +
            "While processing formatQuery(unhex('53454C45435420312046524D2074')). (SYNTAX_ERROR) (version 25.8.33.6 (official build))"
        val result = ParseOnlyClickhouseVerifier({ FailingConnection(SQLException(old)) }).verify("SELECT 1 FRM t")
        assertEquals(VerifyResult.Rejected(VerifyError("Syntax error at t: Expected one of: token, Comma", 1, 14, 13, 14)), result)
    }

    @Test
    fun statementLimitsAreUnverified() {
        for (code in listOf(159, 167, 168, 306, 718)) {
            val failure = SQLException("Code: $code. DB::Exception: limit: In scope SELECT formatQuery(unhex('53')). (LIMIT)")
            assertIs<VerifyResult.Unverified>(ParseOnlyClickhouseVerifier({ FailingConnection(failure) }).verify("SELECT 1"), "code $code")
        }
    }

    private class FailingConnection(private val failure: SQLException) : ClickhouseQueryConnection {
        override fun queryString(sql: String, timeoutSeconds: Int): String? = throw failure
        override fun cancel() = Unit
        override fun close() = Unit
    }

    @Test
    fun insertsWithInlineDataAreRecognizedFromTheirTokens() {
        for (sql in listOf(
            "INSERT INTO t VALUES (1), (2", "INSERT INTO t FORMAT CSV\n1,\"x\n", "insert into t settings async_insert = 1 values (1)",
            "EXPLAIN AST INSERT INTO t VALUES (1), (2", "EXPLAIN SYNTAX INSERT INTO t FORMAT JSONEachRow {\"a\":",
            "INSERT INTO t SELECT a FROM input('a UInt64') FORMAT TSV\n88\n", "INSERT INTO t VALUES",
        )) assertTrue(hasInlineData(sql), sql)
        for (sql in listOf(
            "INSERT INTO t SELECT 'x FORMAT y'", "INSERT INTO t SELECT * FROM values('a UInt8', 1)", "SELECT 1 FORMAT JSON",
            "SELECT 'INSERT INTO t VALUES'", "INSERT INTO t SELECT 1 -- VALUES (1)",
        )) assertFalse(hasInlineData(sql), sql)
    }
}
