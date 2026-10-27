package dev.brikk.house.sql.verify

import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.duckdb.DuckDBConnection

internal class ConnectionDuckdbVerifierTest {
    @Test
    fun usesAndClosesTheSuppliedHelperWithoutBindingOrExecutingEditorText() {
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { user ->
            user.createStatement().use {
                it.execute("CREATE SEQUENCE s")
                it.execute("CREATE MACRO json_serialize_sql(sql) AS nextval('s')::VARCHAR")
            }
            val helpers = mutableListOf<DuckDBConnection>()
            val verifier = ConnectionDuckdbVerifier({
                user.duplicate().also {
                    helpers += it
                    it.createStatement().use { statement -> statement.execute("SET active_grammar_extensions=[]") }
                }.let(::JdbcDuckdbQueryConnection)
            })
            for (sql in listOf(
                "SELECT * FROM range(nextval('s'))",
                "SELECT * FROM query('SELECT * FROM range(nextval(''s''))')",
                "SELECT 'it''s quoted; DROP SEQUENCE s; --'",
            )) assertIs<VerifyResult.Accepted>(verifier.verify(sql), sql)
            assertIs<VerifyResult.Unverified>(verifier.verify("CREATE TABLE copied AS SELECT * FROM range(nextval('s'))"))
            assertTrue(helpers.all { it.isClosed })
            assertEquals(4, helpers.size)
            user.createStatement().use { statement ->
                statement.executeQuery("SELECT nextval('s'), (SELECT count(*) FROM duckdb_tables() WHERE table_name='copied')").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1), "parse-checking must not bind table-function arguments")
                    assertEquals(0, rows.getInt(2), "parse-checking must not execute DDL")
                }
            }
        }
    }

    @Test
    fun nativeParserByteRangesAndWrappedExpressionRangesMatchTheOriginalText() {
        val verifier = ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(DriverManager.getConnection("jdbc:duckdb:")) })
        val sql = "SELECT '😀'\r\nFROM WHERE"
        val error = assertIs<VerifyResult.Rejected>(verifier.verify(sql)).error
        assertEquals(sql.indexOf("WHERE"), error.offset)
        assertEquals(sql.indexOf("WHERE") + 5, error.endOffset)
        assertEquals(2, error.line)
        assertEquals(6, error.column)
        val expression = assertIs<VerifyResult.Rejected>(verifier.verifyExpression("'😀' +")).error
        assertEquals(5, expression.offset)
        assertEquals(6, expression.column)
    }

    @Test
    fun missingGrammarAndInvalidTransportResponsesNeverBecomeSqlRejections() {
        val missing = ConnectionDuckdbVerifier({
            val helper = DriverManager.getConnection("jdbc:duckdb:")
            try {
                helper.createStatement().use { it.execute("SET active_grammar_extensions=['not_registered']") }
                JdbcDuckdbQueryConnection(helper)
            } catch (e: SQLException) {
                helper.close()
                throw e
            }
        })
        assertContains(assertIs<VerifyResult.Unavailable>(missing.verify("SELECT 1")).reason, "not_registered")
        assertIs<VerifyResult.Unavailable>(ConnectionDuckdbVerifier({ null }).verify("SELECT 1"))
        for (response in listOf(null, "", "not json", "{}", "[]", "{\"error\":false}", "{\"error\":\"false\",\"statements\":[]}", "{\"error\":true}")) {
            val helper = StubConnection { response }
            val result = ConnectionDuckdbVerifier({ helper }).verify("SELECT 1")
            assertIs<VerifyResult.Unavailable>(result, response)
            assertTrue(helper.closed.get())
        }
    }

    @Test
    fun cancellationClosesAHelperThatFinishesOpeningLateWithoutSubmittingSql() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val helper = object : DuckdbQueryConnection {
            override fun queryString(sql: String, timeoutSeconds: Int): String? = error("SQL submitted after cancellation")
            override fun cancel() = Unit
            override fun close() { closed.countDown() }
        }
        val canceled = CancellationException("typing resumed")
        val verifier = ConnectionDuckdbVerifier(connect = {
            opening.countDown()
            // Model a driver connection open that does not honor thread interruption.
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                    // It will still return a helper, which the verifier must close.
                }
            }
            helper
        }, checkCanceled = {
            if (opening.count == 0L) throw canceled
        })
        try {
            assertSame(canceled, assertFailsWith<CancellationException> { verifier.verify("SELECT 1") })
        } finally {
            release.countDown()
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS), "late helper leaked")
    }

    @Test
    fun cancellationAndDeadlineRequestDriverCancelAndCloseTheHelper() {
        fun blockingHelper(started: CountDownLatch, canceled: CountDownLatch, closed: CountDownLatch) = object : DuckdbQueryConnection {
            override fun queryString(sql: String, timeoutSeconds: Int): String? {
                started.countDown()
                canceled.await(5, TimeUnit.SECONDS)
                return "{\"error\":false,\"statements\":[]}"
            }
            override fun cancel() { canceled.countDown() }
            override fun close() { closed.countDown() }
        }
        val started = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stop = AtomicBoolean()
        val verifier = ConnectionDuckdbVerifier({ blockingHelper(started, canceled, closed) }, checkCanceled = {
            if (stop.get()) throw CancellationException("canceled")
        })
        val result = CompletableFuture.supplyAsync { runCatching { verifier.verify("SELECT 1") }.exceptionOrNull() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        stop.set(true)
        assertIs<CancellationException>(result.get(5, TimeUnit.SECONDS))
        assertTrue(canceled.await(5, TimeUnit.SECONDS))
        assertTrue(closed.await(5, TimeUnit.SECONDS))

        val timedOut = CountDownLatch(1)
        val timedClosed = CountDownLatch(1)
        val deadline = ConnectionDuckdbVerifier({ blockingHelper(CountDownLatch(1), timedOut, timedClosed) }, timeoutSeconds = 1)
        assertContains(assertIs<VerifyResult.Unavailable>(deadline.verify("SELECT 1")).reason, "timed out")
        assertTrue(timedOut.await(5, TimeUnit.SECONDS))
        assertTrue(timedClosed.await(5, TimeUnit.SECONDS))
    }

    private class StubConnection(private val response: () -> String?) : DuckdbQueryConnection {
        val closed = AtomicBoolean()
        override fun queryString(sql: String, timeoutSeconds: Int): String? = response()
        override fun cancel() = Unit
        override fun close() { closed.set(true) }
    }
}
