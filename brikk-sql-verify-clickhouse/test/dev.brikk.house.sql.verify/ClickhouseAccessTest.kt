package dev.brikk.house.sql.verify

import dev.brikk.house.sql.verify.ClickhouseServer.run
import com.clickhouse.jdbc.ClickHouseDriver
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
                assertIs<VerifyResult.Unavailable>(verifierAs(user).verify("SELECT 1"))
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
