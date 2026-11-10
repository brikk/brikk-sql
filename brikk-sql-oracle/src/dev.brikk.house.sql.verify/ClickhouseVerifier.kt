package dev.brikk.house.sql.verify

import dev.brikk.house.chdb.Chdb
import dev.brikk.house.chdb.ChdbConfig
import dev.brikk.house.chdb.ChdbQueryException
import dev.brikk.house.chdb.ChdbSession
import dev.brikk.house.chdb.ChdbUnavailableException
import dev.brikk.house.chdb.ChdbOutputFormat

/**
 * ClickHouse's native grammar verifier, backed in-process by chDB.
 *
 * `EXPLAIN AST` normally parses only, but a leading settings list such as
 * `optimize = 1 SELECT ...` runs the analyzer: it can fetch `url()` schemas, evaluate scalar
 * subqueries and load dictionaries. The SQL's own `SETTINGS` also
 * apply to the EXPLAIN query. This oracle is not a safe parse-only boundary for untrusted SQL;
 * the editor verifier uses `formatQuery` over hex data instead (see brikk-sql-verify-clickhouse/README.md).
 *
 * It is a syntax oracle, not a semantic validator: an unknown table/function or another
 * non-syntax engine failure is reported as [VerifyResult.Accepted]. No catalog is required
 * from the caller, but analysis triggered by the input can access engine resources.
 *
 * A compatible `libchdb` is selected by [ChdbConfig.libraryPath], the `brikk.chdb.library`
 * system property, or a matching `brikk-chdb-native-*` classpath resource. No ClickHouse server,
 * CLI, JDBC driver, or child process is involved. Call [close] when the verifier is no longer needed.
 * A [verify] or [verifyExpression] after [close] returns [VerifyResult.Unavailable].
 */
public class ClickhouseVerifier private constructor(
    private val session: ChdbSession?,
    private val unavailableReason: String?,
) : SqlVerifier, AutoCloseable {
    override val engine: String = "clickhouse"

    private var closed = false

    @Synchronized
    override fun verify(sql: String): VerifyResult {
        val activeSession = session ?: return VerifyResult.Unavailable(
            unavailableReason ?: "ClickHouse verification was not performed: libchdb is unavailable.",
        )
        if (closed) return VerifyResult.Unavailable("ClickHouse verification is unavailable: the verifier is closed.")
        return try {
            activeSession.query("$QUERY_PREFIX$sql", ChdbOutputFormat.TSV)
            VerifyResult.Accepted
        } catch (error: ChdbQueryException) {
            classifyClickhouseFailure(sql, error.message.orEmpty())
        }
    }

    /** ClickHouse has no expression-only parser entry point, so wrap the fragment in SELECT. */
    override fun verifyExpression(sql: String): VerifyResult =
        verify("$EXPRESSION_PREFIX$sql").withoutPrefix(EXPRESSION_PREFIX)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session?.close()
    }

    public companion object {
        private const val EXPRESSION_PREFIX = "SELECT "

        /**
         * Creates a verifier without throwing for an unavailable native library. In that case
         * [verify] returns [VerifyResult.Unavailable], leaving the caller free to continue
         * on unsupported platforms such as Windows.
         */
        public fun create(config: ChdbConfig = ChdbConfig()): ClickhouseVerifier =
            try {
                ClickhouseVerifier(Chdb.open(config), unavailableReason = null)
            } catch (error: ChdbUnavailableException) {
                ClickhouseVerifier(session = null, unavailableReason = error.message)
            }
    }
}

private const val QUERY_PREFIX = "EXPLAIN AST "
private val POSITION = Regex("""position\s+(\d+)""", RegexOption.IGNORE_CASE)

internal fun classifyClickhouseFailure(sql: String, message: String): VerifyResult {
    val syntaxError = message.contains("Syntax error", ignoreCase = true) || message.contains("Code: 62", ignoreCase = true)
    if (!syntaxError) {
        // EXPLAIN AST did reach the engine, and this error is not from the parser. This
        // matches the contract of every verifier in this module: grammar accepted.
        return VerifyResult.Accepted
    }
    // ClickHouse reports a 1-based UTF-8 byte position in the whole query, EXPLAIN AST included.
    val position = POSITION.find(message)?.groupValues?.get(1)?.toIntOrNull()
    val offset = position?.let { utf16OffsetOfByte(sql, it - 1 - QUERY_PREFIX.length) }
    return VerifyResult.Rejected(offset?.let { VerifyError.atOffset(sql, message, it) } ?: VerifyError(message))
}

/** The UTF-16 offset of the UTF-8 [byteOffset] in [text], or null unless it starts a character. */
private fun utf16OffsetOfByte(text: String, byteOffset: Int): Int? {
    var bytes = 0
    var index = 0
    while (bytes < byteOffset && index < text.length) {
        val codePoint = text.codePointAt(index)
        bytes += when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        index += Character.charCount(codePoint)
    }
    return index.takeIf { bytes == byteOffset }
}
