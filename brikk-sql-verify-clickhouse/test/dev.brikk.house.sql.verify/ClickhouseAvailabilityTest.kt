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

    /** The 0.9.8 driver's compressed transport turns the server's message into this: no verdict. */
    @Test
    fun anUnreadableServerMessageIsNoVerdict() {
        val unreadable = SQLException("Code: 62. DB::Exception: <Unreadable error message> (transport error: 400) (queryId= 8ca1)")
        val result = ParseOnlyClickhouseVerifier({ FailingConnection(unreadable) }).verify("SELECT 1; S")
        assertIs<VerifyResult.Unavailable>(result)
        assertTrue("unreadable" in result.reason, result.reason)
    }

    /** Errors in the verifier's own query, or past the size limit inside the statement, are no verdict on the statement. */
    @Test
    fun wrapperAndSizeErrorsAreUnverified() {
        val inside = "Code: 62. DB::Exception: Max query size exceeded (can be increased with the `max_query_size` setting): " +
            "Syntax error: failed at position 201 (+): +. : In scope SELECT formatQuery(unhex('5345')). (SYNTAX_ERROR)"
        val wrapper = "Code: 62. DB::Exception: Syntax error: failed at position 26 ('5345'): '5345'. Expected one of: token. (SYNTAX_ERROR)"
        for (message in listOf(inside, wrapper)) {
            assertIs<VerifyResult.Unverified>(ParseOnlyClickhouseVerifier({ FailingConnection(SQLException(message)) }).verify("SELECT 1"), message)
        }
    }

    private class FailingConnection(private val failure: SQLException) : ClickhouseQueryConnection {
        override fun queryString(sql: String, timeoutSeconds: Int): String? = throw failure
        override fun cancel() = Unit
        override fun close() = Unit
    }

    @Test
    fun anInsertWithInlineDataIsRecognizedFromTheServersFormatting() {
        assertTrue("INSERT INTO t FORMAT Values".isInsertWithData())
        assertTrue("INSERT INTO t\nSETTINGS async_insert = 1\nFORMAT CSV".isInsertWithData())
        assertFalse("INSERT INTO t SELECT 1".isInsertWithData())
        assertFalse("SELECT 1\nFORMAT JSON".isInsertWithData())
    }
}
