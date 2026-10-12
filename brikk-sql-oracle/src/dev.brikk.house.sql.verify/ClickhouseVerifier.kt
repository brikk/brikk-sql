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
 * `EXPLAIN AST` invokes ClickHouse's parser without executing the submitted statement. It is a
 * syntax oracle, not a semantic validator: an unknown table/function or a setting-dependent
 * failure means ClickHouse got beyond parsing and is therefore reported as accepted. This
 * verifier has no catalog dependency; callers that need function name/arity evidence may layer
 * a catalog or a stronger analysis check on top, but neither is required for AST verification.
 *
 * A compatible `libchdb` is selected by [ChdbConfig.libraryPath], the `brikk.chdb.library`
 * system property, or a matching `brikk-chdb-native-*` classpath resource. No ClickHouse server,
 * CLI, JDBC driver, or child process is involved. Call [close] when the verifier is no longer needed.
 */
class ClickhouseVerifier private constructor(
    private val session: ChdbSession?,
    private val unavailableReason: String?,
) : SqlVerifier, AutoCloseable {
    override val engine: String = "clickhouse"

    @Synchronized
    override fun verify(sql: String): VerifyResult {
        val activeSession = session ?: return VerifyResult.Unavailable(
            unavailableReason ?: "ClickHouse verification was not performed: libchdb is unavailable.",
        )
        return try {
            activeSession.query("EXPLAIN AST $sql", ChdbOutputFormat.TSV)
            VerifyResult.Accepted
        } catch (error: ChdbQueryException) {
            classify(sql, error.message.orEmpty())
        }
    }

    /** ClickHouse has no expression-only parser entry point, so wrap the fragment in SELECT. */
    override fun verifyExpression(sql: String): VerifyResult =
        verify("$EXPRESSION_PREFIX$sql").withoutPrefix(EXPRESSION_PREFIX)

    override fun close() {
        session?.close()
    }

    private fun classify(sql: String, message: String): VerifyResult {
        if (!isSyntaxError(message)) {
            // EXPLAIN AST did reach the engine, and this error is not from the parser. This
            // matches the contract of every verifier in this module: grammar accepted.
            return VerifyResult.Accepted
        }
        val lineAndCol = LINE_AND_COLUMN.find(message)
        val position = POSITION.find(message)?.groupValues?.get(1)?.toIntOrNull()
        val error = when {
            lineAndCol != null -> VerifyError(
                message,
                line = lineAndCol.groupValues[1].toIntOrNull(),
                column = lineAndCol.groupValues[2].toIntOrNull(),
            )
            position != null -> VerifyError.atOffset(sql, message, position - 1)
            else -> VerifyError(message)
        }
        return VerifyResult.Rejected(error)
    }

    companion object {
        private const val EXPRESSION_PREFIX = "SELECT "
        private val POSITION = Regex("""position\s+(\d+)""", RegexOption.IGNORE_CASE)
        private val LINE_AND_COLUMN = Regex(
            """line\s+(\d+),\s*column\s+(\d+)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Creates a verifier without throwing for an unavailable native library. In that case
         * [verify] returns [VerifyResult.Unavailable], leaving the caller free to continue
         * on unsupported platforms such as Windows.
         */
        fun create(config: ChdbConfig = ChdbConfig()): ClickhouseVerifier =
            try {
                ClickhouseVerifier(Chdb.open(config), unavailableReason = null)
            } catch (error: ChdbUnavailableException) {
                ClickhouseVerifier(session = null, unavailableReason = error.message)
            }

        private fun isSyntaxError(message: String): Boolean =
            message.contains("Syntax error", ignoreCase = true) ||
                message.contains("Code: 62", ignoreCase = true)
    }
}
