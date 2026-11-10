package dev.brikk.house.sql.verify

import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class ClickhouseWorkBoundsTest {
    @Test
    fun oversizedInputIsUnverifiedWithoutConnectingAndUtf8StillGetsTheExactLimit() {
        val connections = AtomicInteger()
        val verifier = ParseOnlyClickhouseVerifier({
            connections.incrementAndGet()
            StubConnection { "formatted" }
        })
        val tooLong = VerifyResult.Unverified("The statement is too long for ClickHouse parse-checking.")
        assertEquals(tooLong, verifier.verify("a".repeat(100_001)))
        assertEquals(tooLong, verifier.verify("ü".repeat(50_001)))
        assertEquals(0, connections.get())
        assertIs<VerifyResult.Accepted>(verifier.verify("a".repeat(100_000)))
        assertIs<VerifyResult.Accepted>(verifier.verify("ü".repeat(50_000)))
        assertEquals(2, connections.get())
    }

    @Test
    fun aHungConnectTimesOutAndInterruptsItsWorker() = withStartedWorker { limits, opening ->
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val verifier = verifier(limits, connect = {
            opening.countDown()
            try {
                release.await()
            } catch (_: InterruptedException) {
                interrupted.countDown()
            }
            null
        })
        try {
            val result = CompletableFuture.supplyAsync { verifier.verify("SELECT 1") }
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            assertContains(assertIs<VerifyResult.Unavailable>(result.get(5, TimeUnit.SECONDS)).reason, "timed out")
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "deadline did not cancel the worker")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun theDeadlineClosesAConnectionThatFinishesOpeningAfterInterruption() = withStartedWorker { limits, opening ->
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val verifier = verifier(limits, connect = {
            opening.countDown()
            awaitIgnoringInterrupts(release)
            object : ClickhouseQueryConnection {
                override fun queryString(sql: String, timeoutSeconds: Int): String = error("SQL submitted after deadline")
                override fun cancel() = Unit
                override fun close() = closed.countDown()
            }
        })
        try {
            val result = CompletableFuture.supplyAsync { verifier.verify("SELECT 1") }
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            assertContains(assertIs<VerifyResult.Unavailable>(result.get(5, TimeUnit.SECONDS)).reason, "timed out")
        } finally {
            release.countDown()
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun theDeadlineRequestsDriverCancellationWithoutWaitingForTheQuery() = withStartedWorker { limits, querying ->
        val canceled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val verifier = verifier(limits, connect = {
            object : ClickhouseQueryConnection {
                override fun queryString(sql: String, timeoutSeconds: Int): String {
                    querying.countDown()
                    awaitIgnoringInterrupts(release)
                    return "formatted"
                }
                override fun cancel() = canceled.countDown()
                override fun close() = closed.countDown()
            }
        })
        try {
            val result = CompletableFuture.supplyAsync { verifier.verify("SELECT 1") }
            assertTrue(querying.await(5, TimeUnit.SECONDS))
            assertContains(assertIs<VerifyResult.Unavailable>(result.get(5, TimeUnit.SECONDS)).reason, "timed out")
            assertTrue(canceled.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun nonInterruptibleCallsCannotGrowThePoolAndSaturationReturnsPromptly() = withLimits { limits ->
        val entered = CountDownLatch(CLICKHOUSE_VERIFY_WORKERS)
        val release = CountDownLatch(1)
        val stop = AtomicBoolean()
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        val verifier = verifier(limits, connect = {
            threads += Thread.currentThread()
            entered.countDown()
            awaitIgnoringInterrupts(release)
            null
        }, checkCanceled = { if (stop.get()) throw CancellationException("edited") })
        val callers = Executors.newFixedThreadPool(CLICKHOUSE_VERIFY_WORKERS)
        try {
            val results = (1..CLICKHOUSE_VERIFY_WORKERS).map {
                callers.submit<Throwable?> { runCatching { verifier.verify("SELECT 1") }.exceptionOrNull() }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stop.set(true)
            results.forEach { assertIs<CancellationException>(it.get(5, TimeUnit.SECONDS)) }
            // Future.cancel cannot stop these connects: their threads remain occupied.
            val saturated = CompletableFuture.supplyAsync {
                (1..64).map { verifier.verify("SELECT 1") }
            }.get(5, TimeUnit.SECONDS)
            saturated.forEach { assertContains(assertIs<VerifyResult.Unavailable>(it).reason, "capacity is exhausted") }
            assertEquals(CLICKHOUSE_VERIFY_WORKERS, threads.size)
            assertEquals(CLICKHOUSE_VERIFY_WORKERS, (limits.workers as ThreadPoolExecutor).largestPoolSize)
            assertEquals(0, limits.workers.queue.size)
        } finally {
            release.countDown()
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun saturatedCancellationWorkersNeverBlockOrReplaceTheCallersException() = withLimits { limits ->
        val cancelsEntered = CountDownLatch(CLICKHOUSE_CANCEL_WORKERS)
        val releaseCancels = CountDownLatch(1)
        val releaseQueries = CountDownLatch(1)
        try {
            repeat(CLICKHOUSE_CANCEL_WORKERS + 2) {
                val querying = CountDownLatch(1)
                val cancelled = CancellationException("edited $it")
                val verifier = verifier(limits, connect = {
                    object : ClickhouseQueryConnection {
                        override fun queryString(sql: String, timeoutSeconds: Int): String {
                            querying.countDown()
                            awaitIgnoringInterrupts(releaseQueries)
                            return "formatted"
                        }
                        override fun cancel() {
                            cancelsEntered.countDown()
                            awaitIgnoringInterrupts(releaseCancels)
                        }
                        override fun close() = Unit
                    }
                }, checkCanceled = { if (querying.count == 0L) throw cancelled })
                val result = CompletableFuture.supplyAsync { runCatching { verifier.verify("SELECT 1") }.exceptionOrNull() }
                assertTrue(querying.await(5, TimeUnit.SECONDS))
                assertSame(cancelled, result.get(5, TimeUnit.SECONDS))
                // Ensure each early cancel owns a worker before the next call tries to cancel.
                if (it == CLICKHOUSE_CANCEL_WORKERS - 1) assertTrue(cancelsEntered.await(5, TimeUnit.SECONDS))
            }
            assertEquals(CLICKHOUSE_CANCEL_WORKERS, (limits.cancellers as ThreadPoolExecutor).largestPoolSize)
            assertEquals(0, limits.cancellers.queue.size)
        } finally {
            releaseQueries.countDown()
            releaseCancels.countDown()
        }
    }

    @Test
    fun cancellationClosesAConnectionThatOpensLateWithoutSubmittingSql() = withLimits { limits ->
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelled = IllegalStateException("IDE cancellation hook")
        val verifier = verifier(limits, connect = {
            opening.countDown()
            awaitIgnoringInterrupts(release)
            object : ClickhouseQueryConnection {
                override fun queryString(sql: String, timeoutSeconds: Int): String = error("SQL submitted after cancellation")
                override fun cancel() = Unit
                override fun close() = closed.countDown()
            }
        }, checkCanceled = { if (opening.count == 0L) throw cancelled })
        try {
            assertSame(cancelled, assertFailsWith<IllegalStateException> { verifier.verify("SELECT 1") })
        } finally {
            release.countDown()
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aServerTimeoutRemainsTheServersFailureRatherThanAClientTimeout() = withLimits { limits ->
        val queried = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val verifier = verifier(limits, timeoutSeconds = 1, connect = {
            StubConnection { timeout ->
                assertEquals(1, timeout)
                queried.countDown()
                check(answer.await(5, TimeUnit.SECONDS))
                throw SQLException("Code: 159. DB::Exception: server execution timed out (TIMEOUT_EXCEEDED)")
            }
        })
        val result = CompletableFuture.supplyAsync { verifier.verify("SELECT 1") }
        try {
            assertTrue(queried.await(5, TimeUnit.SECONDS))
        } finally {
            answer.countDown()
        }
        assertContains(assertIs<VerifyResult.Unverified>(result.get(5, TimeUnit.SECONDS)).reason, "server execution timed out")
    }

    @Test
    fun settingsRefusalStillAllowsTheServersTimeoutOnTheSecondAttempt() = withLimits { limits ->
        val attempts = AtomicInteger()
        val verifier = verifier(limits, timeoutSeconds = 1, connect = {
            object : ClickhouseQueryConnection {
                override fun queryString(sql: String, timeoutSeconds: Int): String {
                    assertEquals(1, timeoutSeconds)
                    if (attempts.incrementAndGet() == 1) {
                        assertContains(sql, "SETTINGS readonly = 1, max_execution_time = 1")
                        throw SQLException("Code: 164. DB::Exception: settings are readonly")
                    }
                    assertEquals("SELECT formatQuery(unhex('53454C4543542031'))", sql)
                    throw SQLException("Code: 159. DB::Exception: server execution timed out (TIMEOUT_EXCEEDED)")
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
        })
        assertContains(assertIs<VerifyResult.Unverified>(verifier.verify("SELECT 1")).reason, "server execution timed out")
        assertEquals(2, attempts.get())
    }

    @Test
    fun nonPositiveTimeoutsStillReachTheDriverAndTheClientRuleDoesNotOverflow() {
        for (timeout in listOf(Int.MIN_VALUE, -1, 0, 1, 5, Int.MAX_VALUE)) {
            assertEquals(30L + 2 * timeout.toLong().coerceAtLeast(0), TimeUnit.NANOSECONDS.toSeconds(clickhouseClientDeadlineNanos(timeout)))
            val verifier = ParseOnlyClickhouseVerifier({
                StubConnection { actual ->
                    assertEquals(timeout, actual)
                    "formatted"
                }
            }, timeoutSeconds = timeout)
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1"))
        }
    }

    private fun verifier(
        limits: ClickhouseVerificationLimits,
        connect: () -> ClickhouseQueryConnection?,
        timeoutSeconds: Int = 5,
        checkCanceled: () -> Unit = {},
    ) = clickhouseVerifierWithLimits(connect, timeoutSeconds, checkCanceled, limits)

    private fun withStartedWorker(test: (ClickhouseVerificationLimits, CountDownLatch) -> Unit) = withLimits(100) { limits ->
        val entered = CountDownLatch(1)
        val workers = object : ExecutorService by limits.workers {
            override fun <T> submit(task: Callable<T>): Future<T> {
                val future = limits.workers.submit(task)
                // Even if thread startup exceeds the client deadline, cancellation cannot
                // happen until the fake connect/query has actually been entered.
                assertTrue(entered.await(5, TimeUnit.SECONDS), "worker did not enter the fake driver")
                return future
            }
        }
        test(ClickhouseVerificationLimits(limits.deadlineNanos, workers, limits.cancellers), entered)
    }

    // Non-deadline tests use their latches and five-second assertions, not the client timeout.
    private fun withLimits(deadlineMillis: Long = 60_000, test: (ClickhouseVerificationLimits) -> Unit) {
        val workers = clickhouseVerificationWorkers("test-clickhouse-verify", CLICKHOUSE_VERIFY_WORKERS)
        val cancellers = clickhouseVerificationWorkers("test-clickhouse-cancel", CLICKHOUSE_CANCEL_WORKERS)
        try {
            test(ClickhouseVerificationLimits(TimeUnit.MILLISECONDS.toNanos(deadlineMillis), workers, cancellers))
        } finally {
            workers.shutdownNow()
            cancellers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "verification workers leaked")
            assertTrue(cancellers.awaitTermination(5, TimeUnit.SECONDS), "cancellation workers leaked")
        }
    }

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) {
            try {
                check(latch.await(10, TimeUnit.SECONDS)) { "test did not release blocked driver" }
                return
            } catch (_: InterruptedException) {
                // Model a broken driver, but always release it from the test's finally block.
            }
        }
    }

    private class StubConnection(private val query: (Int) -> String) : ClickhouseQueryConnection {
        override fun queryString(sql: String, timeoutSeconds: Int): String = query(timeoutSeconds)
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
