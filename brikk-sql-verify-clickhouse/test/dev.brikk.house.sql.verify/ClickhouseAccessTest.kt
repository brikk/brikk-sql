package dev.brikk.house.sql.verify

import dev.brikk.house.sql.verify.ClickhouseServer.run
import com.clickhouse.jdbc.ClickHouseDriver
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A profile that forbids changing settings still gets verdicts; a login the server refuses gets none.
 * ClickHouse's own HTTP interface carries the readonly logins: the JDBC driver adds its own settings
 * (`async_insert`) to each request, which a `readonly = 1` profile refuses before any query runs.
 */
internal class ClickhouseAccessTest {
    private fun verifierAs(user: String, password: String = "") = ParseOnlyClickhouseVerifier({
        JdbcClickhouseQueryConnection(
            ClickHouseDriver().connect(ClickhouseServer.require(), Properties().apply {
                setProperty("user", user)
                setProperty("password", password)
                setProperty("compress", "false")
                setProperty("decompress", "false")
            }) ?: error("no connection"),
        )
    })

    @Test
    fun readonlyProfilesGetVerdicts() {
        ClickhouseServer.require()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        ClickhouseServer.open().use { admin ->
            for (level in 1..2) admin.run("CREATE USER verify_ro${level}_$suffix IDENTIFIED WITH no_password SETTINGS readonly = $level")
            try {
                for (level in 1..2) {
                    val verifier = ParseOnlyClickhouseVerifier({ HttpClickhouseQueryConnection(HTTP, "verify_ro${level}_$suffix") })
                    assertEquals(VerifyResult.Accepted, verifier.verify("SELECT 1"), "readonly = $level")
                    assertIs<VerifyResult.Rejected>(verifier.verify("SELECT FROM WHERE"), "readonly = $level")
                }
            } finally {
                for (level in 1..2) admin.run("DROP USER IF EXISTS verify_ro${level}_$suffix")
            }
        }
    }

    @Test
    fun theJdbcDriversOwnSettingsMakeAReadonlyOneLoginUnavailable() {
        ClickhouseServer.require()
        val user = "verify_jdbc_ro_" + UUID.randomUUID().toString().replace("-", "")
        ClickhouseServer.open().use { admin ->
            admin.run("CREATE USER $user IDENTIFIED WITH no_password SETTINGS readonly = 1")
            try {
                // 26.9 refuses them (Unavailable); 25.8 lets a readonly login set them to their defaults (Accepted).
                val result = verifierAs(user).verify("SELECT 1")
                assertTrue(result is VerifyResult.Unavailable || result == VerifyResult.Accepted, result.toString())
            } finally {
                admin.run("DROP USER IF EXISTS $user")
            }
        }
    }

    /** A statement over the server's `max_query_size` (here, the hex wrapper) is not checked, never rejected at a wrapper offset. */
    @Test
    fun aStatementOverTheQuerySizeLimitIsUnverified() {
        ClickhouseServer.require()
        val user = "verify_small_" + UUID.randomUUID().toString().replace("-", "")
        ClickhouseServer.open().use { admin ->
            admin.run("CREATE USER $user IDENTIFIED WITH no_password SETTINGS max_query_size = 600")
            try {
                val verifier = ParseOnlyClickhouseVerifier({ HttpClickhouseQueryConnection(HTTP, user) })
                assertIs<VerifyResult.Unverified>(verifier.verify("SELECT " + "1 + ".repeat(100) + "1"))
                assertIs<VerifyResult.Rejected>(verifier.verify("SELECT FROM WHERE"))
            } finally {
                admin.run("DROP USER IF EXISTS $user")
            }
        }
    }

    /** A login on the old analyzer gets positioned rejections too; skipped where the server no longer has it (26.9). */
    @Test
    fun theOldAnalyzerStillRejects() {
        ClickhouseServer.require()
        val user = "verify_old_" + UUID.randomUUID().toString().replace("-", "")
        ClickhouseServer.open().use { admin ->
            val created = runCatching { admin.run("CREATE USER $user IDENTIFIED WITH no_password SETTINGS allow_experimental_analyzer = 0") }
            org.junit.jupiter.api.Assumptions.assumeTrue(created.isSuccess, "this server has no old analyzer")
            try {
                val verifier = ParseOnlyClickhouseVerifier({ HttpClickhouseQueryConnection(HTTP, user) })
                val sql = "SELECT 'é' FRM t"
                val error = (verifier.verify(sql) as? VerifyResult.Rejected)?.error
                assertEquals(sql.lastIndexOf('t'), error?.offset, error.toString())
                assertTrue(error?.message.orEmpty().startsWith("Syntax error at t: Expected one of:"), error?.message)
            } finally {
                admin.run("DROP USER IF EXISTS $user")
            }
        }
    }

    @Test
    fun aRefusedLoginIsUnavailable() {
        ClickhouseServer.require()
        assertIs<VerifyResult.Unavailable>(verifierAs("default", "wrong").verify("SELECT 1"))
    }

    private companion object {
        /** The HTTP interface behind [ClickhouseServer]'s JDBC URL. */
        val HTTP: String get() = ClickhouseServer.require().replace("jdbc:clickhouse://", "http://").substringBeforeLast('/')
    }
}
