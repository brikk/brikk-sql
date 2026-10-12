package dev.brikk.house.sql.verify

import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties
import org.duckdb.DuckDBDriver

/**
 * Verifies SQL with DuckDB's parser alone, safe for editors: nothing the user types is bound or
 * run. DuckDB evaluates table-function arguments while binding (`range(nextval('s'))`, also inside
 * `query(...)` or `CREATE TABLE ... AS`), so this verifier only calls `json_serialize_sql` and
 * never falls back to `EXPLAIN` or prepare.
 * Statements it cannot serialize, which is everything but SELECT, are [VerifyResult.Unverified].
 *
 * It runs on [driver], so an editor can verify with the user's own DuckDB version. The connection
 * is an in-memory database with external access, extension installation and autoloading off, and
 * its configuration locked. A driver that cannot open it makes the verifier
 * [VerifyResult.Unavailable] for good; a connection that dies is dropped, that call is
 * [VerifyResult.Unavailable], and the next call reopens it. Error positions are converted from
 * DuckDB's code points.
 *
 * Calls are synchronized over one lazily opened connection; [close] releases it.
 */
public class ParseOnlyDuckdbVerifier(private val driver: Driver = DuckDBDriver()) : SqlVerifier, AutoCloseable {
    override val engine: String = "duckdb"

    private var connection: Connection? = null
    private var unavailableReason: String? = null

    @Synchronized
    override fun verify(sql: String): VerifyResult {
        val open = connection ?: connect() ?: return VerifyResult.Unavailable(unavailableReason ?: CLOSED)
        // The SQL travels as a string literal, which ends at a NUL.
        if ('\u0000' in sql) return VerifyResult.Unverified("DuckDB cannot parse-check SQL that contains a NUL character.")
        val serialized = try {
            open.serializeSql(sql)
        } catch (e: SQLException) {
            if (open.isUsable()) return VerifyResult.Unverified("DuckDB could not parse-check this statement: ${e.reason()}")
            connection = null
            open.closeQuietly()
            return VerifyResult.Unavailable("DuckDB verification is unavailable: the connection failed (${e.reason()}).")
        }
        return when (serialized) {
            SerializedSql.Parsed -> VerifyResult.Accepted
            is SerializedSql.Failed -> if (serialized.type == "parser") {
                VerifyResult.Rejected(
                    serialized.errorIn(sql, "Parser Error: ${serialized.message.orEmpty()}"),
                )
            } else {
                VerifyResult.Unverified(
                    "Parse-only DuckDB verification cannot check this statement: " +
                        listOfNotNull(serialized.type, serialized.message).joinToString(": "),
                )
            }
        }
    }

    /** DuckDB has no standalone expression entry point, so the fragment is wrapped as `SELECT <expr>`. */
    override fun verifyExpression(sql: String): VerifyResult =
        verify("$EXPRESSION_PREFIX$sql").withoutPrefix(EXPRESSION_PREFIX)

    /** Opens the restricted connection and checks the parser channel works on this driver. */
    private fun connect(): Connection? {
        if (unavailableReason != null) return null
        val opened = try {
            driver.connect(URL, restrictedSettings())
                ?: return unavailable("the driver does not accept $URL")
        } catch (e: SQLException) {
            return unavailable("the driver could not open an in-memory database (${e.reason()})")
        } catch (e: LinkageError) {
            // A user's driver jar may fail to load its native library or classes.
            return unavailable("the driver could not be loaded (${e::class.java.simpleName}: ${e.message})")
        }
        var usable = false
        try {
            val problem = try {
                when (val probe = opened.serializeSql(PROBE)) {
                    SerializedSql.Parsed -> null
                    is SerializedSql.Failed -> probe.message ?: probe.document
                }
            } catch (e: SQLException) {
                e.reason()
            }
            if (problem != null) return unavailable("json_serialize_sql is not usable on this driver ($problem)")
            usable = true
            connection = opened
            return opened
        } finally {
            if (!usable) opened.closeQuietly()
        }
    }

    private fun Connection.isUsable(): Boolean = try {
        !isClosed && isValid(0)
    } catch (_: SQLException) {
        false
    }

    private fun Connection.closeQuietly() {
        try {
            close()
        } catch (_: SQLException) {
            // The connection is being discarded; its close failure changes nothing.
        }
    }

    /** DuckDB appends the failing query from `LINE 1:` on; that is the wrapper, not the user's SQL. */
    private fun SQLException.reason(): String =
        message?.substringBefore("\nLINE ")?.trim()?.ifEmpty { null } ?: this::class.java.simpleName

    private fun unavailable(detail: String): Connection? {
        unavailableReason = "DuckDB verification is unavailable: $detail."
        return null
    }

    @Synchronized
    override fun close() {
        unavailableReason = CLOSED
        val open = connection
        connection = null
        open?.close()
    }

    private companion object {
        const val URL = "jdbc:duckdb:"
        const val PROBE = "SELECT 1"
        const val EXPRESSION_PREFIX = "SELECT "
        const val CLOSED = "DuckDB verification is unavailable: the verifier is closed."

        fun restrictedSettings() = Properties().apply {
            setProperty("enable_external_access", "false")
            setProperty("autoinstall_known_extensions", "false")
            setProperty("autoload_known_extensions", "false")
            setProperty("lock_configuration", "true")
            setProperty("threads", "1")
            setProperty("memory_limit", "256MB")
        }
    }
}
