package dev.brikk.house.sql.verify

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * The corpus-gate oracle: verifies SQL against the real DuckDB parser via an embedded in-memory
 * engine (`org.duckdb:duckdb_jdbc`, pinned to engine 1.5.6 to match the generated DuckDB
 * function catalog).
 *
 * Strategy (two channels, because DuckDB reports parse failures both ways):
 *  1. `SELECT json_serialize_sql(...)`, a pure parse with no binding. It does NOT throw on
 *     invalid SQL: it returns a JSON document carrying the error type, message and position.
 *  2. `json_serialize_sql` only supports SELECT statements, so other statements fall back to a
 *     JDBC prepare, which parses AND binds. There a `Parser Error:` prefix means the grammar
 *     rejected it, while binder/catalog errors ("table does not exist") mean the grammar
 *     accepted it, which is all this oracle answers.
 *
 * Binding evaluates some expressions (table-function arguments such as `range(nextval('s'))`),
 * and the engine has external access enabled, so this oracle is for trusted corpus SQL only.
 * Editors use [ParseOnlyDuckdbVerifier].
 *
 * Cold start: the first [verify] boots an embedded DuckDB; the connection is created lazily
 * and kept for the verifier's lifetime. [verify] is synchronized because one connection is
 * shared. Call [close] to release the embedded database.
 */
public class DuckdbVerifier internal constructor(
    private val connect: () -> Connection,
) : SqlVerifier, AutoCloseable {
    public constructor() : this({ DriverManager.getConnection("jdbc:duckdb:") })

    override val engine: String = "duckdb"

    private var lazyConnection: Connection? = null

    private val connection: Connection
        get() = lazyConnection ?: connect().also { lazyConnection = it }

    /** DuckDB has no standalone expression entry point, so the fragment is wrapped as `SELECT <expr>`. */
    override fun verifyExpression(sql: String): VerifyResult =
        verify("$EXPRESSION_PREFIX$sql").withoutPrefix(EXPRESSION_PREFIX)

    @Synchronized
    override fun verify(sql: String): VerifyResult {
        val serialized = try {
            connection.serializeSql(sql)
        } catch (e: Exception) {
            // The parse channel itself failed, so no grammar verdict was obtained.
            return unavailable(e)
        }
        return when (serialized) {
            SerializedSql.Parsed -> VerifyResult.Accepted
            is SerializedSql.Failed -> if (serialized.type == "not implemented") {
                verifyByPrepare(sql)
            } else {
                val message = listOfNotNull(serialized.type, serialized.message).joinToString(": ")
                VerifyResult.Rejected(errorAtCodePoint(sql, message.ifEmpty { serialized.document }, serialized.position))
            }
        }
    }

    /**
     * Parse + bind. Grammar rejection surfaces as a `Parser Error:` prefix; known binder/catalog
     * failures mean the parser accepted the SQL. Unexpected failures leave the result unverified.
     */
    private fun verifyByPrepare(sql: String): VerifyResult = try {
        connection.prepareStatement(sql).close()
        VerifyResult.Accepted
    } catch (e: SQLException) {
        classifyDuckdbPrepareFailure(e.message?.trim().orEmpty())
    } catch (e: Exception) {
        unavailable(e)
    }

    private fun unavailable(error: Throwable): VerifyResult = VerifyResult.Unavailable(
        "DuckDB verification was not performed: ${error::class.simpleName}: " +
            (error.message?.trim() ?: "no detail"),
    )

    @Synchronized
    override fun close() {
        lazyConnection?.close()
        lazyConnection = null
    }

    private companion object {
        const val EXPRESSION_PREFIX = "SELECT "
    }
}

private val DUCKDB_LINE_MARKER = Regex("""(?m)^LINE (\d+):""")

internal fun classifyDuckdbPrepareFailure(message: String): VerifyResult = when {
    message.startsWith("Parser Error", ignoreCase = true) -> VerifyResult.Rejected(
        VerifyError(message, line = DUCKDB_LINE_MARKER.find(message)?.groupValues?.get(1)?.toIntOrNull()),
    )
    listOf(
        "Binder Error",
        "Catalog Error",
        "Not implemented Error",
        "IO Error: No files found that match the pattern",
    )
        .any { message.startsWith(it, ignoreCase = true) } -> VerifyResult.Accepted
    else -> VerifyResult.Unverified(
        "DuckDB verification was not performed: unexpected prepare failure" +
            if (message.isBlank()) "." else ": $message",
    )
}
