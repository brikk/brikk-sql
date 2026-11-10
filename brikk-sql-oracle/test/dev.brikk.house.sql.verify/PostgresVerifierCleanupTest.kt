package dev.brikk.house.sql.verify

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CancellationException
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostgresVerifierCleanupTest {
    @Test
    fun deallocateFailureKeepsAcceptanceAndRetiresConnection() {
        val jdbc = FakeJdbc(deallocateFailure = SQLException("deallocate failed"))
        withVerifier(jdbc) { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1"))
            assertEquals(listOf("PREPARE brikk_v_0 AS SELECT 1", "DEALLOCATE brikk_v_0", "close"), jdbc.events)
            assertRetired(verifier, jdbc)
            val reason = assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 2")).reason
            assertTrue(reason.contains("DEALLOCATE failed"), reason)
            assertTrue(reason.contains("deallocate failed"), reason)
        }
    }

    @Test
    fun connectionFailureDuringExecutionKeepsItsReasonAndSkipsRetiredConnectionCleanup() {
        val jdbc = FakeJdbc(
            fallback = true, executionFailure = postgresError("08006", "original connection failure"),
            rollbackFailure = SQLException("This connection has been closed"),
        )
        withVerifier(jdbc) { verifier ->
            val result = assertIs<VerifyResult.Unavailable>(verifier.verify("CREATE TABLE t (a INT)"))
            assertTrue(result.reason.contains("connection failed"), result.reason)
            assertTrue(result.reason.contains("original connection failure"), result.reason)
            assertEquals(listOf(false), jdbc.autoCommitWrites)
            assertTrue("rollback" !in jdbc.events)
            repeat(2) {
                assertEquals(result.reason, assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 2")).reason)
            }
            assertRetired(verifier, jdbc)
        }
    }

    @Test
    fun closeErrorCannotLeaveTheConnectionAvailableForReuse() {
        val closeError = AssertionError("close failed")
        val jdbc = FakeJdbc(
            fallback = true, rollbackFailure = SQLException("rollback failed"), closeFailure = closeError,
        )
        withVerifier(jdbc) { verifier ->
            assertSame(closeError, assertFailsWith<AssertionError> { verifier.verify("CREATE TABLE t (a INT)") })
            assertEquals(listOf(false), jdbc.autoCommitWrites)
            assertEquals(listOf("rollback", "close"), jdbc.events.takeLast(2))
            val events = jdbc.events.toList()
            repeat(2) {
                val reason = assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 2")).reason
                assertTrue(reason.contains("rollback failed"), reason)
            }
            assertEquals(events, jdbc.events, "close failure must not leave the connection reusable")
        }
    }

    @Test
    fun rollbackFailureClosesWithoutRestoringAutoCommitAndKeepsAcceptance() {
        val jdbc = FakeJdbc(fallback = true, rollbackFailure = SQLException("rollback failed"))
        withVerifier(jdbc) { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("CREATE TABLE t (a INT)"))
            assertEquals(listOf(false), jdbc.autoCommitWrites)
            assertEquals(listOf("rollback", "close"), jdbc.events.takeLast(2))
            assertRetired(verifier, jdbc)
        }
    }

    @Test
    fun autoCommitRestoreFailureKeepsAcceptanceAndRetiresConnection() {
        val jdbc = FakeJdbc(fallback = true, restoreFailure = SQLException("restore failed"))
        withVerifier(jdbc) { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("CREATE TABLE t (a INT)"))
            assertEquals(listOf(false, true), jdbc.autoCommitWrites)
            assertEquals(listOf("rollback", "autoCommit=true", "close"), jdbc.events.takeLast(3))
            assertRetired(verifier, jdbc)
        }
    }

    @Test
    fun cleanupFailurePreservesRejectionMessageAndPosition() {
        val sql = "SELECT 1 +\nFROM"
        for (rollbackFails in listOf(true, false)) {
            val original = postgresError("42601", "original syntax error", sql.indexOf("FROM") + 1)
            val cleanup = SQLException("cleanup failed")
            val jdbc = FakeJdbc(
                fallback = true, executionFailure = original,
                rollbackFailure = cleanup.takeIf { rollbackFails },
                restoreFailure = cleanup.takeUnless { rollbackFails },
            )
            withVerifier(jdbc) { verifier ->
                val error = assertIs<VerifyResult.Rejected>(verifier.verify(sql)).error
                assertEquals("42601: original syntax error", error.message)
                assertEquals(2, error.line)
                assertEquals(1, error.column)
                assertEquals(sql.indexOf("FROM"), error.offset)
                assertSame(cleanup, original.suppressed.single())
                assertRetired(verifier, jdbc)
            }
        }
    }

    @Test
    fun cleanupFailurePreservesClassifiedAcceptance() {
        for (state in listOf("42P01", "25001")) {
            val jdbc = FakeJdbc(
                fallback = true, executionFailure = postgresError(state, "semantic error"),
                rollbackFailure = SQLException("rollback failed"),
            )
            withVerifier(jdbc) { verifier ->
                assertIs<VerifyResult.Accepted>(verifier.verify("CREATE TABLE t (a INT)"))
                assertRetired(verifier, jdbc)
            }
        }
    }

    @Test
    fun cleanupFailureIsSuppressedOnTheOriginalThrownFailure() {
        for (rollbackFails in listOf(true, false)) {
            for (original in listOf(IllegalStateException("original"), CancellationException("cancelled"), AssertionError("original"))) {
                val cleanup = SQLException("cleanup failed")
                val jdbc = FakeJdbc(
                    fallback = true, executionFailure = original,
                    rollbackFailure = cleanup.takeIf { rollbackFails },
                    restoreFailure = cleanup.takeUnless { rollbackFails },
                )
                withVerifier(jdbc) { verifier ->
                    assertSame(original, assertFailsWith<Throwable> { verifier.verify("CREATE TABLE t (a INT)") })
                    assertSame(cleanup, original.suppressed.single())
                    assertRetired(verifier, jdbc)
                }
            }
        }
    }

    @Test
    fun successfulPrepareCleanupReusesConnection() {
        val jdbc = FakeJdbc()
        withVerifier(jdbc) { verifier ->
            repeat(2) { assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1")) }
            assertEquals(
                listOf("PREPARE brikk_v_0 AS SELECT 1", "DEALLOCATE brikk_v_0",
                    "PREPARE brikk_v_1 AS SELECT 1", "DEALLOCATE brikk_v_1"),
                jdbc.events,
            )
            assertEquals(emptyList(), jdbc.autoCommitWrites)
        }
    }

    @Test
    fun successfulRollbackRestoresAutoCommitAndReusesConnection() {
        for (initialAutoCommit in listOf(true, false)) {
            val jdbc = FakeJdbc(fallback = true, initialAutoCommit = initialAutoCommit)
            withVerifier(jdbc) { verifier ->
                repeat(2) { assertIs<VerifyResult.Accepted>(verifier.verify("CREATE TABLE t (a INT)")) }
                assertEquals(listOf(false, initialAutoCommit, false, initialAutoCommit), jdbc.autoCommitWrites)
                assertEquals(2, jdbc.events.count { it == "rollback" })
                assertEquals(initialAutoCommit, jdbc.connection.autoCommit)
                assertTrue("close" !in jdbc.events)
            }
        }
    }

    private fun withVerifier(jdbc: FakeJdbc, block: (PostgresVerifier) -> Unit) {
        var connections = 0
        PostgresVerifier(connectionFactory = {
            connections += 1
            check(connections == 1) { "must not reconnect after cleanup failure" }
            jdbc.connection
        }).use { verifier ->
            block(verifier)
            assertEquals(1, connections)
        }
    }

    private fun assertRetired(verifier: PostgresVerifier, jdbc: FakeJdbc) {
        assertTrue(jdbc.closed)
        val events = jdbc.events.toList()
        repeat(2) { assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 2")) }
        assertEquals(events, jdbc.events, "later calls must not touch the retired connection")
    }

    private class FakeJdbc(
        val fallback: Boolean = false,
        val executionFailure: Throwable? = null,
        val deallocateFailure: Throwable? = null,
        val rollbackFailure: Throwable? = null,
        val restoreFailure: Throwable? = null,
        val closeFailure: Throwable? = null,
        initialAutoCommit: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val autoCommitWrites = mutableListOf<Boolean>()
        var closed = false
        private var autoCommit = initialAutoCommit

        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            when (method.name) {
                "createStatement" -> {
                    check(!closed) { "retired connection reused" }
                    statement()
                }
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> {
                    val value = args!![0] as Boolean
                    events += "autoCommit=$value"
                    autoCommitWrites += value
                    if (autoCommitWrites.size == 2) restoreFailure?.let { throw it }
                    autoCommit = value
                    null
                }
                "rollback" -> {
                    events += "rollback"
                    rollbackFailure?.let { throw it }
                    null
                }
                "close" -> {
                    events += "close"
                    closeFailure?.let { throw it }
                    closed = true
                    null
                }
                else -> error("Unexpected Connection.${method.name}")
            }
        } as Connection

        private fun statement(): Statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Statement::class.java)) { _, method, args ->
            when (method.name) {
                "execute" -> {
                    val sql = args!![0] as String
                    events += sql
                    when {
                        sql.startsWith("PREPARE ") -> if (fallback) throw postgresError("42601", "not preparable")
                        sql.startsWith("DEALLOCATE ") -> deallocateFailure?.let { throw it }
                        else -> executionFailure?.let { throw it }
                    }
                    false
                }
                "close" -> null
                else -> error("Unexpected Statement.${method.name}")
            }
        } as Statement
    }

    private companion object {
        fun postgresError(state: String, message: String, position: Int = 0): PSQLException =
            PSQLException(ServerErrorMessage("SERROR\u0000C$state\u0000M$message\u0000P$position\u0000\u0000"))
    }
}
