package dev.brikk.house.sql.verify

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverPropertyInfo
import java.util.Properties
import java.util.logging.Logger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.duckdb.DuckDBDriver

/**
 * The editor verifier must never execute user SQL. DuckDB evaluates table-function arguments
 * while binding, so `EXPLAIN` of each statement below advances sequence `s` (the control proves
 * it); the verifier must leave `s` untouched.
 */
internal class ParseOnlyDuckdbSafetyTest {
    private val scratch = Files.createTempDirectory(Path.of("build").createDirectories(), "parse-only-safety-")
    private val database = scratch.resolve("safety.duckdb")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun deleteScratch() = scratch.deleteRecursively()

    private val statements = listOf(
        "SELECT * FROM range(nextval('s'))",
        "SELECT * FROM query('SELECT * FROM range(nextval(''s''))')",
        "CREATE TABLE copied AS SELECT * FROM range(nextval('s'))",
        "EXPLAIN SELECT * FROM range(nextval('s'))",
    )

    @Test
    fun verifyingNeverAdvancesASequence() {
        withDatabase { it.createStatement().use { statement -> statement.execute("CREATE SEQUENCE s") } }

        val results = ParseOnlyDuckdbVerifier(FileDriver(database)).use { verifier -> statements.map(verifier::verify) }
        assertIs<VerifyResult.Accepted>(results[0])
        assertIs<VerifyResult.Accepted>(results[1])
        assertIs<VerifyResult.Unverified>(results[2])
        assertIs<VerifyResult.Unverified>(results[3])

        withDatabase { connection ->
            assertEquals(1, connection.scalar("SELECT nextval('s')"), "the verifier advanced the sequence")
            assertEquals(0, connection.scalar("SELECT count(*) FROM duckdb_tables() WHERE table_name = 'copied'"))
        }
    }

    @Test
    fun explainingTheSameStatementsDoesAdvanceTheSequence() {
        withDatabase { connection ->
            connection.createStatement().use { statement ->
                for (sql in statements) {
                    statement.execute("CREATE OR REPLACE SEQUENCE s")
                    statement.execute(if (sql.startsWith("EXPLAIN")) sql else "EXPLAIN $sql")
                    assertEquals(2, connection.scalar("SELECT nextval('s')"), "binding no longer evaluates: $sql")
                }
            }
        }
    }

    private fun <T> withDatabase(block: (Connection) -> T): T =
        DuckDBDriver().connect("jdbc:duckdb:$database", Properties()).use(block)

    private fun Connection.scalar(sql: String): Int = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    /** Points the verifier's in-memory URL at [database], keeping the verifier's settings. */
    private class FileDriver(private val database: Path) : Driver {
        override fun connect(url: String, info: Properties): Connection =
            DuckDBDriver().connect("jdbc:duckdb:$database", info)
        override fun acceptsURL(url: String) = url.startsWith("jdbc:duckdb:")
        override fun getPropertyInfo(url: String, info: Properties) = emptyArray<DriverPropertyInfo>()
        override fun getMajorVersion() = 1
        override fun getMinorVersion() = 0
        override fun jdbcCompliant() = false
        override fun getParentLogger(): Logger = Logger.getGlobal()
    }
}
