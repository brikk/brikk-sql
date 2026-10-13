package dev.brikk.house.sql.verify

import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 */
public class ParseOnlyClickhouseVerifier(
    private val connect: () -> ClickhouseQueryConnection?,
    private val timeoutSeconds: Int = 5,
    private val checkCanceled: () -> Unit = {},
) : SqlVerifier {
    override val engine: String = "clickhouse"

    /** Set once the server refused the query-level settings; later calls ask without them. */
    private val settingsRefused = AtomicBoolean()

    override fun verify(sql: String): VerifyResult {
        val hex = sql.toByteArray(Charsets.UTF_8).toHex()
        if (hex.length > MAX_HEX_LENGTH) return VerifyResult.Unverified("The statement is too long for ClickHouse parse-checking.")
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

    /** Opens a connection, asks the server to format the SQL whose UTF-8 is [hex], and closes it; all on a worker. */
    private fun ask(hex: String): Answer {
        val running = AtomicBoolean()
        var connection: ClickhouseQueryConnection? = null
        val future: Future<Answer> = WORKERS.submit(Callable {
            val opened = try {
                connect() ?: return@Callable Answer.NoConnection("ClickHouse verification is unavailable: no ClickHouse data source.")
            } catch (e: SQLException) {
                return@Callable Answer.NoConnection("ClickHouse verification is unavailable: the connection failed (${e.reason()}).")
            }
            opened.use {
                synchronized(running) {
                    connection = opened
                    running.set(true)
                }
                format(opened, hex)
            }
        })
        try {
            while (true) {
                try {
                    return future.get(POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    checkCanceled()
                }
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            cancel(future, running) { connection }
            throw CancellationException("ClickHouse verification was interrupted").apply { initCause(e) }
        } catch (e: Throwable) {
            cancel(future, running) { connection }
            throw e
        }
    }

    /** Cancels the query on a worker, since a driver's cancel may itself wait on the server, and interrupts the call. */
    private fun cancel(future: Future<*>, running: AtomicBoolean, connection: () -> ClickhouseQueryConnection?) {
        val open = synchronized(running) { if (running.get()) connection() else null }
        if (open != null) WORKERS.execute { runCatching { open.cancel() } }
        future.cancel(true)
    }

    private fun format(connection: ClickhouseQueryConnection, hex: String): Answer {
        val query = "SELECT formatQuery(unhex('$hex'))"
        if (!settingsRefused.get()) {
            try {
                return Answer.Formatted(connection.queryString("$query SETTINGS readonly = 1, max_execution_time = $timeoutSeconds", timeoutSeconds).orEmpty())
            } catch (e: SQLException) {
                if (e.serverCode() != READONLY) return Answer.Failed(e)
                settingsRefused.set(true)
            }
        }
        return try {
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
                // Every error in the supplied text is reported in formatQuery's scope; one without it is the wrapper's.
                !inFormatQuery() -> VerifyResult.Unverified("ClickHouse could not parse-check this statement: the verification query failed ($detail)")
                detail.startsWith("Max query size exceeded") -> VerifyResult.Unverified("The statement is longer than the server's max_query_size allows to parse-check.")
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

        /** Errors this statement causes without being invalid: TIMEOUT_EXCEEDED, TOO_DEEP_AST, TOO_BIG_AST, TOO_DEEP_RECURSION. */
        val STATEMENT_LIMITS = setOf(159, 167, 168, 306)
        const val POLL_MILLIS = 20L

        /** Keeps the query under ClickHouse's default `max_query_size` (256 KiB). */
        const val MAX_HEX_LENGTH = 200_000
        const val EXPRESSION_PREFIX = "SELECT "
        const val UNREADABLE = "<Unreadable error message>"
        const val INSERT_DATA = "ClickHouse parses an INSERT's header only; its inline data is checked when it runs."

        val WORKERS: ExecutorService = Executors.newCachedThreadPool { task ->
            Thread(task, "brikk-clickhouse-verify").apply { isDaemon = true }
        }
    }
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

/** Whether the server reported the error while formatQuery parsed its argument, not while parsing the verifier's own query. */
private fun SQLException.inFormatQuery(): Boolean = chain().any { it.message?.contains(IN_FORMAT_QUERY) == true }

private const val IN_FORMAT_QUERY = ": In scope SELECT formatQuery"

/** The server's message after `DB::Exception: `, without the echo of the verifier's own query. */
private fun SQLException.serverMessage(): String {
    val message = chain().mapNotNull { it.message }.firstOrNull { CODE_IN.containsMatchIn(it) } ?: return reason()
    return message.substringAfter("DB::Exception: ").substringBefore(IN_FORMAT_QUERY).substringBefore(" (SYNTAX_ERROR)")
        .substringBefore(" (version ").trim().removeSuffix(".")
}

private fun Throwable.chain(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }

private fun Throwable.reason(): String = message?.lineSequence()?.firstOrNull()?.trim()?.ifEmpty { null } ?: this::class.java.simpleName

private val CODE_IN = Regex("""Code: (\d+)\. """)

/**
 * A syntax error from ClickHouse's [detail] (`Syntax error: failed at position N (token) ...: rest.
 * Expected ...`), positioned in [sql] at its 1-based UTF-8 byte position and spanning the token
 * when the text there is the token.
 */
internal fun syntaxError(sql: String, detail: String): VerifyError {
    val match = POSITION.find(detail)
        ?: return VerifyError(detail)
    val head = detail.substring(0, match.range.first).trimEnd().removeSuffix(":")
    val token = match.groupValues[2]
    val expected = detail.indexOf(". Expected ", match.range.last).takeIf { it >= 0 }?.let { detail.substring(it + 2) }
    val atToken = if (token == "end of query") "at the end of the query" else "at $token"
    val message = listOfNotNull("$head $atToken", expected).joinToString(": ")
    val byte = match.groupValues[1].toInt() - 1
    val located = VerifyError.atUtf8Byte(sql, message, byte)
    val offset = located.offset ?: return located
    return if (token != "end of query" && sql.startsWith(token, offset)) located.copy(endOffset = offset + token.length) else located
}

/**
 * Whether [sql], read with core's ClickHouse tokens, holds an INSERT whose rows follow as inline data:
 * a `VALUES` before the INSERT's query, or a `FORMAT`, after an `INSERT` at its parenthesis depth (also under `EXPLAIN`),
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
            // After the INSERT's query, `values(...)` is a table function; FORMAT still names the data's format (`input()`).
            TokenType.VALUES -> if (insertDepth == depth && !query) return true
            TokenType.FORMAT -> if (insertDepth == depth) return true
            else -> Unit
        }
    }
    return lexed.failureOffset != null && insertDepth != null
}

private val POSITION = Regex("""failed at position (\d+) \(((?:[^()]|\([^()]*\))*)\)(?: \(line \d+, col \d+\))?""")


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
