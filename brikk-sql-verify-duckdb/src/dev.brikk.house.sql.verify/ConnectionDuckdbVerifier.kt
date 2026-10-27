package dev.brikk.house.sql.verify

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Parse-only verification on the caller's DuckDB instance and grammar profile.
 * Each call obtains an isolated helper from [connect], runs only `json_serialize_sql`, and closes
 * the helper. The supplier must initialize the exact ordered active grammar extensions and any
 * routing before returning it. Do not supply the user's console connection or an unrelated memory
 * database. Missing grammar or initialization failures should throw [SQLException] or return null;
 * they are unavailable, never a rejection from a substitute base grammar.
 *
 * No user SQL is prepared, bound or executed. Non-SELECT serialization remains unverified.
 * [timeoutSeconds] bounds the whole call, including connection initialization. [checkCanceled] is
 * polled while the worker runs; its exceptions propagate after requesting cancellation. A helper
 * that finishes opening after cancellation is closed without submitting the parser query.
 */
public class ConnectionDuckdbVerifier(
    private val connect: () -> DuckdbQueryConnection?,
    private val timeoutSeconds: Int = 10,
    private val checkCanceled: () -> Unit = {},
) : SqlVerifier {
    init {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
    }

    override val engine: String = "duckdb"

    override fun verify(sql: String): VerifyResult {
        checkCanceled()
        if ('\u0000' in sql) return VerifyResult.Unverified("DuckDB cannot parse-check SQL that contains a NUL character.")
        val call = Call()
        val future = WORKERS.submit(Callable {
            try {
                val opened = connect() ?: return@Callable VerifyResult.Unavailable("DuckDB verification is unavailable: no matching connection.")
                opened.use {
                    call.opened(opened)
                    val document = opened.queryString(serializeSqlQuery(sql), timeoutSeconds)
                    serializedSqlDocument(document)?.toParseOnlyResult(sql)
                        ?: VerifyResult.Unavailable("DuckDB verification is unavailable: invalid parser response.")
                }
            } catch (e: SQLException) {
                VerifyResult.Unavailable("DuckDB verification is unavailable: ${e.reason()}.")
            }
        })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        try {
            while (true) {
                checkCanceled()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    call.cancel()
                    future.cancel(true)
                    return VerifyResult.Unavailable("DuckDB verification is unavailable: timed out.")
                }
                try {
                    return future.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    // Poll the IDE's cancellation even when the driver ignores query timeouts.
                }
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            call.cancel()
            future.cancel(true)
            throw CancellationException("DuckDB verification was interrupted").apply { initCause(e) }
        } catch (e: Throwable) {
            call.cancel()
            future.cancel(true)
            throw e
        }
    }

    override fun verifyExpression(sql: String): VerifyResult =
        verify("SELECT $sql").withoutPrefix("SELECT ")

    private class Call {
        private var canceled = false
        private var connection: DuckdbQueryConnection? = null

        @Synchronized
        fun opened(opened: DuckdbQueryConnection) {
            if (canceled) throw CancellationException("DuckDB verification was canceled")
            connection = opened
        }

        fun cancel() {
            val opened = synchronized(this) {
                canceled = true
                connection
            } ?: return
            // A driver's cancel can block; it must not block the caller's cancellation.
            WORKERS.execute { runCatching { opened.cancel() } }
        }
    }

    private companion object {
        val WORKERS = Executors.newCachedThreadPool { task ->
            Thread(task, "brikk-duckdb-verify").apply { isDaemon = true }
        }
    }
}

/** An initialized, short-lived helper owned and closed by [ConnectionDuckdbVerifier]. */
public interface DuckdbQueryConnection : AutoCloseable {
    /** Runs the verifier's one-column query, returning the first string or null when no row exists. */
    public fun queryString(sql: String, timeoutSeconds: Int): String?

    /** Requests cancellation from another thread without closing the user's connection. */
    public fun cancel()

    override fun close()
}

/** A helper over a JDBC [connection], which it owns and closes. */
public class JdbcDuckdbQueryConnection(private val connection: Connection) : DuckdbQueryConnection {
    @Volatile
    private var running: java.sql.Statement? = null

    override fun queryString(sql: String, timeoutSeconds: Int): String? = connection.createStatement().use { statement ->
        running = statement
        try {
            statement.queryTimeout = timeoutSeconds
            statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
        } finally {
            running = null
        }
    }

    override fun cancel() {
        running?.cancel()
    }

    override fun close() {
        connection.close()
    }
}

/** Never echo the wrapper query, which can contain connection or SQL secrets. */
private fun SQLException.reason(): String =
    message?.substringBefore("\nLINE ")?.trim()?.ifEmpty { null } ?: this::class.java.simpleName
