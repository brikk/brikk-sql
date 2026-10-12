package dev.brikk.house.sql.verify

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.duckdb.DuckDBDriver

internal class ParseOnlyDuckdbVerifierTest {
    @Test
    fun acceptsSelectsAndExpressions() {
        ParseOnlyDuckdbVerifier().use { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT a FROM t WHERE b = 1"))
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT * FROM completely_unknown_table"))
            assertIs<VerifyResult.Accepted>(verifier.verifyExpression("x -> '$.family'"))
            assertEquals("duckdb", verifier.engine)
        }
    }

    @Test
    fun rejectionsPointAtTheOffendingTokenAcrossSupplementaryCharactersAndCrlf() {
        ParseOnlyDuckdbVerifier().use { verifier ->
            for (payload in listOf("ascii", "\uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE80")) {
                for (separator in listOf(" ", "\n", "\r\n")) {
                    val sql = "SELECT '$payload',$separator'$payload' FROM WHERE"
                    val where = sql.indexOf("WHERE")
                    val error = assertIs<VerifyResult.Rejected>(verifier.verify(sql), sql).error
                    assertEquals(
                        VerifyError(
                            "Parser Error: syntax error at or near \"WHERE\"",
                            line = sql.take(where).count { it == '\n' } + 1,
                            column = where - (sql.lastIndexOf('\n', where - 1) + 1) + 1,
                            offset = where,
                        ),
                        error,
                        sql,
                    )
                }
            }
            val expression = assertIs<VerifyResult.Rejected>(verifier.verifyExpression("'\uD83D\uDE00' +")).error
            assertEquals(listOf(1, 7, 6), listOf(expression.line, expression.column, expression.offset))
        }
    }

    @Test
    fun statementsItCannotSerializeAreUnverifiedNotAccepted() {
        ParseOnlyDuckdbVerifier().use { verifier ->
            for (sql in listOf("CREATE TABLE t (a INT)", "INSERT INTO t VALUES (1)", "EXPLAIN SELECT 1")) {
                val result = assertIs<VerifyResult.Unverified>(verifier.verify(sql), sql)
                assertContains(result.reason, "not implemented")
            }
        }
    }

    @Test
    fun failureReasonsDoNotQuoteTheWrapperQuery() {
        ParseOnlyDuckdbVerifier().use { verifier ->
            val reason = assertIs<VerifyResult.Unverified>(verifier.verify("SELECT 'a\u0000b'")).reason
            assertFalse("json_serialize_sql" in reason, reason)
        }
    }

    @Test
    fun aDeadConnectionIsUnavailableAndReopenedOnTheNextCall() {
        val driver = RecordingDriver()
        ParseOnlyDuckdbVerifier(driver).use { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1"))
            driver.connections.single().close()
            assertContains(assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 2")).reason, "connection")
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 3"))
            assertEquals(2, driver.connections.size)
        }
    }

    @Test
    fun theConnectionIsRestrictedAndLocked() {
        val driver = RecordingDriver()
        ParseOnlyDuckdbVerifier(driver).use { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1"))
            val connection = driver.connections.single()
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT current_setting('enable_external_access'), current_setting('autoinstall_known_extensions'), " +
                        "current_setting('autoload_known_extensions'), current_setting('lock_configuration')",
                ).use { rows ->
                    rows.next()
                    assertEquals(listOf(false, false, false, true), (1..4).map { rows.getBoolean(it) })
                }
                val locked = assertFailsWith<SQLException> { statement.execute("SET enable_external_access = true") }
                assertContains(locked.message.orEmpty(), "configuration has been locked")
            }
        }
    }

    @Test
    fun driversThatCannotServeAreUnavailableForGood() {
        val failures = listOf(
            "does not accept" to { _: String, _: Properties -> null },
            "could not open" to { _: String, _: Properties -> throw SQLException("no memory") },
            "could not be loaded" to { _: String, _: Properties -> throw UnsatisfiedLinkError("libduckdb_java.so") },
        )
        for ((reason, connect) in failures) {
            var attempts = 0
            val verifier = ParseOnlyDuckdbVerifier(RecordingDriver { url, settings -> attempts += 1; connect(url, settings) })
            repeat(2) {
                assertContains(assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 1")).reason, reason)
            }
            assertEquals(1, attempts, reason)
        }

        val closed = ParseOnlyDuckdbVerifier()
        assertIs<VerifyResult.Accepted>(closed.verify("SELECT 1"))
        closed.close()
        assertContains(assertIs<VerifyResult.Unavailable>(closed.verify("SELECT 1")).reason, "closed")
    }

    @Test
    fun cancellationPropagatesAndKeepsTheConnection() {
        val cancelled = CancellationException("cancelled")
        var armed = false
        val driver = RecordingDriver { url, settings ->
            val real = DuckDBDriver().connect(url, settings)
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (armed && method.name == "createStatement") throw cancelled
                try {
                    method.invoke(real, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as Connection
        }
        ParseOnlyDuckdbVerifier(driver).use { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1"))
            armed = true
            assertSame(cancelled, assertFailsWith<CancellationException> { verifier.verify("SELECT 2") })
            armed = false
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 3"))
            assertEquals(1, driver.connections.size)
        }
    }

    /** Opens DuckDB connections through [connect] and keeps every one it returned. */
    private class RecordingDriver(
        private val connect: (String, Properties) -> Connection? = { url, settings -> DuckDBDriver().connect(url, settings) },
    ) : Driver {
        val connections = mutableListOf<Connection>()

        override fun connect(url: String, info: Properties): Connection? = connect.invoke(url, info)?.also { connections += it }
        override fun acceptsURL(url: String) = url.startsWith("jdbc:duckdb:")
        override fun getPropertyInfo(url: String, info: Properties) = emptyArray<DriverPropertyInfo>()
        override fun getMajorVersion() = 1
        override fun getMinorVersion() = 0
        override fun jdbcCompliant() = false
        override fun getParentLogger(): Logger = Logger.getGlobal()
    }
}
