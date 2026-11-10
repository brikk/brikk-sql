package dev.brikk.house.sql.verify

import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verifies ClickHouse SQL with the parser of the user's own server, safe for editors: nothing the
 * user typed is run, bound or analyzed. The server is asked `SELECT formatQuery(unhex('…'))`, with
 * the SQL as hex data, so it only ever parses the SQL as a string argument: table functions
 * (`url`, `s3`, `remote`, `file`, `input`), dictionaries, scalar subqueries and the SQL's own
 * `SETTINGS` are never evaluated or applied. `EXPLAIN AST` is not used: its settings list
 * (`EXPLAIN AST optimize = 1`) runs the analyzer, which fetches `url()` schemas, evaluates scalar
 * subqueries and loads dictionaries, and the SQL's own `SETTINGS` apply to it.
 *
 * Each call opens a connection with [connect] (null when there is none, such as no data source),
 * runs one query and closes it. The query asks for `readonly = 1` and a [timeoutSeconds] execution
 * limit; a server whose profile forbids changing settings is asked without them, and the query is
 * still read-only by construction. Only a syntax error is a verdict. A statement too deep, too big
 * or too slow to parse is [VerifyResult.Unverified]; every other failure (no connection,
 * authentication, access, no `formatQuery` before ClickHouse 23.10) is [VerifyResult.Unavailable].
 *
 * The server parses an INSERT's header only: inline data (`VALUES` rows, `FORMAT` data) is read at
 * execution. An accepted INSERT with inline data (read from core's tokens, wrapped in EXPLAIN or not)
 * is therefore [VerifyResult.Unverified]. Error
 * positions are converted from ClickHouse's UTF-8 byte offsets.
 *
 * [checkCanceled] is polled while the server works; whatever it throws cancels the query, closes
 * the connection and propagates. An interrupted caller cancels too, with a `CancellationException`.
 * Not single-flight: callers serialize calls if they need to.
 * The client deadline, including connecting, is 30 seconds plus twice the non-negative
 * [timeoutSeconds], allowing both settings attempts their server timeout and connection grace.
 * Non-positive values are still passed unchanged to JDBC and the server, but now have a
 * 30-second client deadline even if the driver treats zero as unlimited.
 * Per classloader, 16 verification workers allow several editors to highlight concurrently;
 * four separate cancellation workers keep a stuck cancel from consuming verification capacity.
 * Neither pool queues work: exhausted capacity returns [VerifyResult.Unavailable], and exhausted
 * cancellation capacity never delays or replaces the caller's cancellation.
 */
public class ParseOnlyClickhouseVerifier(
    private val connect: () -> ClickhouseQueryConnection?,
    private val timeoutSeconds: Int = 5,
    private val checkCanceled: () -> Unit = {},
) : SqlVerifier {
    override val engine: String = "clickhouse"

    private val limits = clickhouseVerificationLimits.get() ?: ClickhouseVerificationLimits(
        clickhouseClientDeadlineNanos(timeoutSeconds), WORKERS, CANCELLERS,
    )

    /** Set once the server refused the query-level settings; later calls ask without them. */
    private val settingsRefused = AtomicBoolean()

    override fun verify(sql: String): VerifyResult {
        if (sql.length > MAX_HEX_LENGTH / 2) return VerifyResult.Unverified(TOO_LONG)
        val hex = sql.toByteArray(Charsets.UTF_8).toHex()
        if (hex.length > MAX_HEX_LENGTH) return VerifyResult.Unverified(TOO_LONG)
        val answer = ask(hex)
        return when (answer) {
            is Answer.Formatted -> if (hasInlineData(sql)) VerifyResult.Unverified(INSERT_DATA) else VerifyResult.Accepted
            is Answer.Failed -> answer.failure.toResult(sql)
            is Answer.NoConnection -> VerifyResult.Unavailable(answer.reason)
        }
    }

    /** ClickHouse has no standalone expression entry point, so the fragment is wrapped as `SELECT <expr>`. */
    override fun verifyExpression(sql: String): VerifyResult =
        verify("$EXPRESSION_PREFIX$sql").withoutPrefix(EXPRESSION_PREFIX)

    private sealed interface Answer {
        class Formatted(val text: String) : Answer

        class Failed(val failure: SQLException) : Answer

        class NoConnection(val reason: String) : Answer
    }

    /**
     * One call's cancellation, kept: a connection that opens after it is closed at once, and nothing is
     * submitted after it.
     */
    private class Call(private val cancellers: ExecutorService) {
        private var canceled = false
        private var connection: ClickhouseQueryConnection? = null

        /** Takes [opened] for this call, or false when the call was canceled first. */
        @Synchronized
        fun opened(opened: ClickhouseQueryConnection): Boolean {
            if (canceled) return false
            connection = opened
            return true
        }

        /** Throws when the call was canceled: checked before each submission. */
        @Synchronized
        fun proceed() {
            if (canceled) throw CancellationException("ClickHouse verification was canceled")
        }

        /** Cancels the running query on a worker, since a driver's cancel may itself wait on the server. */
        fun cancel() {
            val open = synchronized(this) {
                canceled = true
                connection
            } ?: return
            try {
                cancellers.execute { runCatching { open.cancel() } }
            } catch (_: RejectedExecutionException) {
                // Stuck driver cancels already occupy the bounded workers. Still cancel the
                // future without waiting for the driver or replacing the caller's exception.
            }
        }
    }

    /** Opens a connection, asks the server to format the SQL whose UTF-8 is [hex], and closes it; all on a worker. */
    private fun ask(hex: String): Answer {
        val call = Call(limits.cancellers)
        val deadline = System.nanoTime() + limits.deadlineNanos
        val future: Future<Answer> = try { limits.workers.submit(Callable {
            val opened = try {
                connect() ?: return@Callable Answer.NoConnection("ClickHouse verification is unavailable: no ClickHouse data source.")
            } catch (e: SQLException) {
                return@Callable Answer.NoConnection("ClickHouse verification is unavailable: the connection failed (${e.reason()}).")
            }
            opened.use {
                if (!call.opened(opened)) return@Callable Answer.NoConnection("ClickHouse verification was canceled.")
                format(opened, hex, call)
            }
        }) } catch (_: RejectedExecutionException) {
            return Answer.NoConnection("ClickHouse verification is unavailable: verification capacity is exhausted.")
        }
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    // Prefer an answer already delivered (notably the server's timeout) if
                    // the caller was descheduled across its client deadline.
                    if (future.isDone) return future.get()
                    call.cancel()
                    future.cancel(true)
                    return Answer.NoConnection("ClickHouse verification is unavailable: timed out.")
                }
                try {
                    return future.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    checkCanceled()
                }
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            call.cancel()
            future.cancel(true)
            throw CancellationException("ClickHouse verification was interrupted").apply { initCause(e) }
        } catch (e: Throwable) {
            call.cancel()
            future.cancel(true)
            throw e
        }
    }

    private fun format(connection: ClickhouseQueryConnection, hex: String, call: Call): Answer {
        val query = "SELECT formatQuery(unhex('$hex'))"
        if (!settingsRefused.get()) {
            try {
                call.proceed()
                return Answer.Formatted(connection.queryString("$query SETTINGS readonly = 1, max_execution_time = $timeoutSeconds", timeoutSeconds).orEmpty())
            } catch (e: SQLException) {
                if (e.serverCode() != READONLY) return Answer.Failed(e)
                settingsRefused.set(true)
            }
        }
        return try {
            call.proceed()
            Answer.Formatted(connection.queryString(query, timeoutSeconds).orEmpty())
        } catch (e: SQLException) {
            Answer.Failed(e)
        }
    }

    private fun SQLException.toResult(sql: String): VerifyResult {
        val detail = serverMessage()
        // A driver whose transport garbled the answer (the 0.9.8 driver with compression on) says so instead of the server's message.
        if (UNREADABLE in detail) return VerifyResult.Unavailable("ClickHouse verification is unavailable: the driver could not read the server's answer (unreadable error message; turn the driver's compression off).")
        return when (serverCode()) {
            null -> VerifyResult.Unavailable("ClickHouse verification is unavailable: ${reason()}.")
            SYNTAX_ERROR -> when {
                // The verifier's own query is fixed and valid: only its size can fail, here or inside the statement.
                "Max query size exceeded" in detail -> VerifyResult.Unverified("The statement is longer than the server's max_query_size allows to parse-check.")
                detail.startsWith("Empty query") -> VerifyResult.Unverified("ClickHouse has no statement to parse-check here.")
                else -> VerifyResult.Rejected(syntaxError(sql, detail))
            }
            UNKNOWN_FUNCTION -> if ("formatQuery" in detail) {
                VerifyResult.Unavailable("ClickHouse verification is unavailable: the server has no formatQuery (ClickHouse 23.10 or later).")
            } else {
                VerifyResult.Unavailable("ClickHouse verification is unavailable: $detail")
            }
            in STATEMENT_LIMITS -> VerifyResult.Unverified("ClickHouse could not parse-check this statement: $detail")
            // Authentication, access, a missing database, an overloaded server: nothing about this SQL.
            else -> VerifyResult.Unavailable("ClickHouse verification is unavailable: $detail")
        }
    }

    private companion object {
        const val SYNTAX_ERROR = 62
        const val UNKNOWN_FUNCTION = 46
        const val READONLY = 164

        /** Errors this statement causes without being invalid: TIMEOUT_EXCEEDED, TOO_DEEP_AST, TOO_BIG_AST, TOO_DEEP_RECURSION, TOO_SLOW_PARSING. */
        val STATEMENT_LIMITS = setOf(159, 167, 168, 306, 718)
        const val POLL_MILLIS = 20L

        /** Keeps the query under ClickHouse's default `max_query_size` (256 KiB). */
        const val MAX_HEX_LENGTH = 200_000
        const val EXPRESSION_PREFIX = "SELECT "
        const val UNREADABLE = "<Unreadable error message>"
        const val INSERT_DATA = "ClickHouse parses an INSERT's header only; its inline data is checked when it runs."
        private const val TOO_LONG = "The statement is too long for ClickHouse parse-checking."

        val WORKERS: ExecutorService = clickhouseVerificationWorkers("brikk-clickhouse-verify", CLICKHOUSE_VERIFY_WORKERS)
        val CANCELLERS: ExecutorService = clickhouseVerificationWorkers("brikk-clickhouse-verify-cancel", CLICKHOUSE_CANCEL_WORKERS)
    }
}

internal const val CLICKHOUSE_VERIFY_WORKERS = 16
internal const val CLICKHOUSE_CANCEL_WORKERS = 4

internal fun clickhouseClientDeadlineNanos(timeoutSeconds: Int): Long =
    TimeUnit.SECONDS.toNanos(30 + 2 * timeoutSeconds.toLong().coerceAtLeast(0))

internal class ClickhouseVerificationLimits(
    val deadlineNanos: Long,
    val workers: ExecutorService,
    val cancellers: ExecutorService,
)

private val clickhouseVerificationLimits = ThreadLocal<ClickhouseVerificationLimits>()

/** Scoped construction seam: internal parameters without adding even a JVM-public constructor. */
internal fun clickhouseVerifierWithLimits(
    connect: () -> ClickhouseQueryConnection?,
    timeoutSeconds: Int,
    checkCanceled: () -> Unit,
    limits: ClickhouseVerificationLimits,
): ParseOnlyClickhouseVerifier {
    val previous = clickhouseVerificationLimits.get()
    clickhouseVerificationLimits.set(limits)
    try {
        return ParseOnlyClickhouseVerifier(connect, timeoutSeconds, checkCanceled)
    } finally {
        if (previous == null) clickhouseVerificationLimits.remove() else clickhouseVerificationLimits.set(previous)
    }
}

internal fun clickhouseVerificationWorkers(name: String, maximum: Int): ThreadPoolExecutor =
    ThreadPoolExecutor(0, maximum, 60, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, name).apply { isDaemon = true }
    }

/** One short-lived connection to the ClickHouse server a [ParseOnlyClickhouseVerifier] asks. */
public interface ClickhouseQueryConnection : AutoCloseable {
    /**
     * Runs [sql], a query of one String column, and returns its first row's value. A server error
     * throws an `SQLException` whose message (or a cause's) carries ClickHouse's `Code: N. ` text.
     * Waits at most about [timeoutSeconds].
     */
    public fun queryString(sql: String, timeoutSeconds: Int): String?

    /** Cancels the query running on another thread, if any. */
    public fun cancel()

    override fun close()
}

/** A [ClickhouseQueryConnection] over a JDBC [connection], which it closes. */
public class JdbcClickhouseQueryConnection(private val connection: java.sql.Connection) : ClickhouseQueryConnection {
    @Volatile
    private var statement: java.sql.Statement? = null

    override fun queryString(sql: String, timeoutSeconds: Int): String? = connection.createStatement().use { created ->
        statement = created
        try {
            created.queryTimeout = timeoutSeconds
            created.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
        } finally {
            statement = null
        }
    }

    override fun cancel() {
        statement?.cancel()
    }

    override fun close() {
        connection.close()
    }
}

/** ClickHouse's error code in this exception's or a cause's message. */
private fun SQLException.serverCode(): Int? = chain().firstNotNullOfOrNull { it.message?.let(CODE_IN::find) }?.groupValues?.get(1)?.toIntOrNull()

/** Where the server's message names the verifier's query: the new analyzer's scope and the old one's. */
private val QUERY_SUFFIXES = listOf(": In scope SELECT formatQuery", ": While processing formatQuery")

/** The server's message after `DB::Exception: `, without the echo of the verifier's own query. */
private fun SQLException.serverMessage(): String {
    val message = chain().mapNotNull { it.message }.firstOrNull { CODE_IN.containsMatchIn(it) } ?: return reason()
    val text = QUERY_SUFFIXES.fold(message.substringAfter("DB::Exception: ")) { cut, suffix -> cut.substringBefore(suffix) }
    return text.substringBefore(" (SYNTAX_ERROR)")
        .substringBefore(" (version ").trim().removeSuffix(".")
}

private fun Throwable.chain(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }

private fun Throwable.reason(): String = message?.lineSequence()?.firstOrNull()?.trim()?.ifEmpty { null } ?: this::class.java.simpleName

private val CODE_IN = Regex("""Code: (\d+)\. """)

/**
 * A syntax error from ClickHouse's [detail], `Syntax error: failed at position N (token)[ (line L,
 * col C)]: <the rest of the query from there, cut with "...">. <message>`, positioned in [sql] at its
 * 1-based UTF-8 byte position and spanning the token when the text there is the token. The message
 * after the echo is kept (`Expected one of: ...`, `Unmatched parentheses: ...`); the echo is found from
 * [sql] itself, so dots or parentheses in the token or the text do not split it.
 */
internal fun syntaxError(sql: String, detail: String): VerifyError {
    val match = POSITION.find(detail) ?: return VerifyError(detail)
    val head = detail.substring(0, match.range.first).trimEnd().removeSuffix(":")
    val reported = match.groupValues[2]
    val byte = match.groupValues[1].toInt() - 1
    val located = VerifyError.atUtf8Byte(sql, "", byte)
    // Older servers (24.3) quote the token: `('t')`. A string literal's own quotes are in the text.
    val candidates = listOf(reported, reported.removeSurrounding("'")).distinct()
    val token = located.offset?.let { at -> candidates.firstOrNull { sql.startsWith(it, at) } } ?: candidates.last()
    val atToken = if (token == "end of query") "at the end of the query" else "at $token"
    val message = listOfNotNull("$head $atToken", afterEcho(detail.substring(match.range.last + 1), located.offset?.let(sql::substring), sql))
        .joinToString(": ")
    val offset = located.offset ?: return VerifyError(message)
    val end = if (token != "end of query" && token.isNotEmpty() && sql.startsWith(token, offset)) offset + token.length else null
    return VerifyError.atOffset(sql, message, offset, end)
}

/**
 * The message after the server's [echo] of the query. The echo is normally [rest], the text from the
 * error on (null when the position fell outside [sql]), cut with `...` when long; at the end of the
 * query it is the last expression. Failing those, it ends at the first `. ` (or `... `) whose text
 * before occurs in [sql].
 */
private fun afterEcho(echo: String, rest: String?, sql: String): String? {
    val after = when {
        rest != null && echo.startsWith("$rest. ") -> rest.length + 2
        rest != null && echo == rest -> return null
        else -> separators(echo).firstOrNull { (at, _) ->
            val before = echo.substring(0, at)
            rest != null && rest.startsWith(before) && echo.startsWith("$before... ") && at > 0
        }?.let { (at, length) -> at + length }
            ?: separators(echo).firstOrNull { (at, _) -> echo.substring(0, at).let { it.isNotEmpty() && it in sql } }?.let { (at, length) -> at + length }
    }
    return after?.let { echo.substring(it).trim().removeSuffix(".").ifEmpty { null } }
}

/** Where `... ` and `. ` occur in [text], as (offset, length) pairs in order. */
private fun separators(text: String): List<Pair<Int, Int>> = buildList {
    var at = 0
    while (at < text.length) {
        when {
            text.startsWith("... ", at) -> add(at to 4)
            text.startsWith(". ", at) -> add(at to 2)
        }
        at++
    }
}

/**
 * Whether [sql], read with core's ClickHouse tokens, holds an INSERT whose rows follow as inline data:
 * a `VALUES` or `FORMAT` before the INSERT's query, after an `INSERT` at its parenthesis depth (also under `EXPLAIN`),
 * or inline data core already separated. Strings and comments never count; text core cannot
 * tokenize counts when it holds an `INSERT`.
 */
internal fun hasInlineData(sql: String): Boolean {
    val lexed = Tokenizer(TokenizerConfigs.forName("clickhouse")).tokenizeForEditor(sql)
    var depth = 0
    var insertDepth: Int? = null
    var query = false
    for (token in lexed.tokens) {
        when (token.tokenType) {
            TokenType.INSERT_DATA -> return true
            TokenType.L_PAREN -> depth++
            TokenType.R_PAREN -> depth--
            TokenType.INSERT -> if (insertDepth == null) insertDepth = depth
            TokenType.SELECT, TokenType.WITH -> if (insertDepth == depth) query = true
            // After the INSERT's query, `values(...)` is a table function, and a FORMAT has data only when the
            // query reads it (`input()`), which core separates as INSERT_DATA.
            TokenType.VALUES, TokenType.FORMAT -> if (insertDepth == depth && !query) return true
            else -> Unit
        }
    }
    return lexed.failureOffset != null && insertDepth != null
}

private val POSITION = Regex("""failed at position (\d+) \((.*?)\)(?: \(line \d+, col \d+\))?: """, RegexOption.DOT_MATCHES_ALL)


private fun ByteArray.toHex(): String {
    val digits = "0123456789ABCDEF"
    val out = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        out[index * 2] = digits[value ushr 4]
        out[index * 2 + 1] = digits[value and 0x0F]
    }
    return String(out)
}
