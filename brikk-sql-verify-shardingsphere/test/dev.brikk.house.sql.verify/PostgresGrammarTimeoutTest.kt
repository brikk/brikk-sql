package dev.brikk.house.sql.verify

import org.apache.shardingsphere.sql.parser.exception.SQLParsingException
import java.net.URLClassLoader
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class PostgresGrammarTimeoutTest {
    private val executors = mutableListOf<ExecutorService>()
    private val latches = mutableListOf<CountDownLatch>()
    private val loaders = mutableListOf<URLClassLoader>()
    private val slots = Semaphore(2)

    @AfterTest
    fun releaseTestThreadsAndLatches() {
        latches.forEach { it.countDown() }
        executors.forEach { it.shutdown() }
        try {
            for (executor in executors) {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "test worker did not finish")
            }
        } finally {
            loaders.forEach { it.close() }
        }
    }

    @Test
    fun completedPostgresParseIsAcceptedOnAWorkerWithTheOriginalSql() {
        val caller = Thread.currentThread()
        val sql = "SELECT " + "a[".repeat(20) + "1" + "]".repeat(20)
        val result = postgres().verifyWithParser(sql, workers(), 5_000, slots) {
            assertEquals(sql, it)
            assertNotSame(caller, Thread.currentThread())
        }
        assertSame(VerifyResult.Accepted, result)
    }

    @Test
    fun syntaxRejectionKeepsTheExistingMessageAndResultMapping() {
        val failure = SQLParsingException("the original syntax error")
        val expected = shardingSphereParseResult { throw failure }
        val result = postgres().verifyWithParser("bad SQL", workers(), 5_000, slots) { throw failure }
        assertEquals(expected, result)
        assertEquals(failure.message, assertIs<VerifyResult.Rejected>(result).error.message)
    }

    @Test
    fun executorReturnsTheExactCompletedVerdictInstance() {
        val expected = VerifyResult.Rejected(VerifyError("unchanged verdict"))
        assertSame(expected, postgresGrammarCheck(workers(), 5_000, slots) { expected })
    }

    @Test
    fun timeLimitReturnsUnverifiedWithoutWaitingForTheParseToFinish() {
        val pool = workers()
        val started = latch()
        val release = latch()
        val finished = latch()
        val caller = callers().submit<VerifyResult> {
            postgres().verifyWithParser("SELECT 1", pool, 30, slots) {
                started.countDown()
                try {
                    release.await()
                } finally {
                    finished.countDown()
                }
            }
        }
        await(started)
        assertEquals("PostgreSQL grammar check took too long", assertIs<VerifyResult.Unverified>(caller.get(5, TimeUnit.SECONDS)).reason)
        assertEquals(1L, finished.count)
        assertEquals(1, pool.activeCount)
        assertEquals(1, slots.availablePermits())
    }

    @Test
    fun abandonedParsesHoldBothSharedSlotsUntilTheyActuallyFinish() {
        val pool = workers()
        val firstRelease = latch()
        val secondRelease = latch()
        val firstStarted = latch()
        val secondStarted = latch()
        val firstFinished = latch()
        val firstSlotReleased = latch()
        val slots = object : Semaphore(2) {
            override fun release() {
                super.release()
                firstSlotReleased.countDown()
            }
        }
        val first = postgres()
        val second = postgres()
        assertIs<VerifyResult.Unverified>(first.verifyWithParser("first", pool, 30, slots) {
            firstStarted.countDown()
            try {
                firstRelease.await()
            } finally {
                firstFinished.countDown()
            }
        })
        await(firstStarted)
        assertIs<VerifyResult.Unverified>(second.verifyWithParser("second", pool, 30, slots) {
            secondStarted.countDown()
            secondRelease.await()
        })
        await(secondStarted)
        assertEquals(1L, firstFinished.count)
        assertEquals(2, pool.activeCount)
        assertEquals(0, slots.availablePermits())
        val furtherParseStarted = AtomicBoolean()
        val busy = callers().submit<VerifyResult> {
            postgres().verifyWithParser("third", pool, 5_000, slots) { furtherParseStarted.set(true) }
        }.get(1, TimeUnit.SECONDS)
        assertEquals("PostgreSQL grammar check is busy with earlier statements", assertIs<VerifyResult.Unverified>(busy).reason)
        assertFalse(furtherParseStarted.get())

        firstRelease.countDown()
        await(firstFinished)
        await(firstSlotReleased)
        assertSame(VerifyResult.Accepted, first.verifyWithParser("next", pool, 5_000, slots) {})
        assertEquals(1L, secondRelease.count)
        assertEquals(1, slots.availablePermits())
    }

    @Test
    fun sequentialTrivialChecksNeverReportBusyWithBothSharedSlotsFree() {
        assertEquals(2, sharedPostgresGrammarSlots.availablePermits())
        val busy = countSequentialBusyChecks()
        println("PostgreSQL shared pool, both slots free: busy=$busy/20000")
        assertEquals(0, busy)
        assertEquals(2, sharedPostgresGrammarSlots.availablePermits())
    }

    @Test
    fun sequentialTrivialChecksNeverReportBusyWithOneSharedSlotHeld() {
        val started = latch()
        val release = latch()
        try {
            assertIs<VerifyResult.Unverified>(postgres().verifyWithParser(
                "held slot", sharedPostgresGrammarWorkers, 30, sharedPostgresGrammarSlots,
            ) {
                started.countDown()
                release.await()
            })
            await(started)
            assertEquals(1, sharedPostgresGrammarSlots.availablePermits())
            val busy = countSequentialBusyChecks()
            println("PostgreSQL shared pool, one slot held: busy=$busy/20000")
            assertEquals(0, busy)
            assertEquals(1, sharedPostgresGrammarSlots.availablePermits())
        } finally {
            release.countDown()
            assertTrue(sharedPostgresGrammarSlots.tryAcquire(2, 5, TimeUnit.SECONDS), "shared slots were not released")
            sharedPostgresGrammarSlots.release(2)
        }
    }

    @Test
    fun submissionFailureReturnsItsAcquiredSlotAndPropagates() {
        val pool = workers().apply { shutdown() }
        assertFailsWith<RejectedExecutionException> {
            postgresGrammarCheck(pool, 5_000, slots) { error("parse must not start") }
        }
        assertEquals(2, slots.availablePermits())
    }

    @Test
    fun unexpectedParseExceptionPropagatesAsTheOriginalInstance() {
        val failure = IllegalStateException("parser bug")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            postgres().verifyWithParser("SQL", workers(), 5_000, slots) { throw failure }
        })
    }

    @Test
    fun parseCancellationPropagatesAsTheOriginalInstance() {
        val failure = CancellationException("request cancelled")
        assertSame(failure, assertFailsWith<CancellationException> {
            postgres().verifyWithParser("SQL", workers(), 5_000, slots) { throw failure }
        })
    }

    @Test
    fun parserTimeoutAndRejectionExceptionsAreNotMistakenForExecutorOutcomes() {
        for (failure in listOf(TimeoutException("parser failure"), RejectedExecutionException("parser failure"))) {
            assertSame(failure, assertFailsWith<Exception> {
                postgres().verifyWithParser("SQL", workers(), 5_000, slots) { throw failure }
            })
        }
    }

    @Test
    fun unexpectedParseErrorPropagatesAsTheOriginalInstance() {
        val failure = AssertionError("parser failure")
        assertSame(failure, assertFailsWith<AssertionError> {
            postgres().verifyWithParser("SQL", workers(), 5_000, slots) { throw failure }
        })
    }

    @Test
    fun interruptedCallerPropagatesCancellationWithoutReleasingTheParsingWorker() {
        val pool = workers()
        val verifier = postgres()
        val started = latch()
        val release = latch()
        val finished = latch()
        val callerThread = AtomicReference<Thread>()
        val observed = AtomicReference<CancellationException>()
        val interrupted = AtomicBoolean()
        val caller = callers().submit<CancellationException> {
            callerThread.set(Thread.currentThread())
            try {
                verifier.verifyWithParser("SQL", pool, 5_000, slots) {
                    started.countDown()
                    try {
                        release.await()
                    } finally {
                        finished.countDown()
                    }
                }
                error("caller was not interrupted")
            } catch (e: CancellationException) {
                observed.set(e)
                interrupted.set(Thread.currentThread().isInterrupted)
                Thread.interrupted()
                e
            }
        }
        await(started)
        callerThread.get().interrupt()
        val failure = caller.get(5, TimeUnit.SECONDS)
        assertSame(failure, observed.get())
        assertEquals("PostgreSQL verification was interrupted", failure.message)
        assertIs<InterruptedException>(failure.cause)
        assertTrue(interrupted.get())
        assertEquals(1L, finished.count)
        assertEquals(1, pool.activeCount)
        assertEquals(1, slots.availablePermits())
    }

    @Test
    fun mysqlHiveAndClickhouseStillParseOnTheCallerWithoutUsingTheExecutor() {
        val forbidden = workers().apply { shutdown() }
        val caller = Thread.currentThread()
        for ((engine, databaseType) in listOf("mysql" to "MySQL", "hive" to "Hive", "clickhouse" to "ClickHouse")) {
            val result = ShardingSphereVerifier(engine, databaseType).verifyWithParser("SELECT 1", forbidden, 0, Semaphore(0)) {
                assertSame(caller, Thread.currentThread(), engine)
            }
            assertSame(VerifyResult.Accepted, result, engine)
        }
    }

    @Test
    fun workerUsesTheVerifierClassLoaderAndRestoresBothThreadContexts() {
        val foreign = URLClassLoader(emptyArray(), null).also { loaders += it }
        val worker = AtomicReference<Thread>()
        val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), { action ->
            Thread(action, "foreign-context-test").apply {
                isDaemon = true
                contextClassLoader = foreign
                worker.set(this)
            }
        }).also { executors += it }
        val caller = Thread.currentThread()
        val previous = caller.contextClassLoader
        caller.contextClassLoader = foreign
        try {
            val result = postgres().verifyWithParser("SELECT 1", pool, 5_000, slots) {
                assertSame(ShardingSphereVerifier::class.java.classLoader, Thread.currentThread().contextClassLoader)
            }
            assertSame(VerifyResult.Accepted, result)
            assertSame(foreign, caller.contextClassLoader)
            assertSame(foreign, worker.get().contextClassLoader)
        } finally {
            caller.contextClassLoader = previous
        }
    }

    @Test
    fun sharedWorkersUseTwoIdleExpiringDaemonsAndAnUnboundedHandoffQueue() {
        assertEquals(2_000L, POSTGRES_GRAMMAR_CHECK_TIMEOUT_MILLIS)
        assertEquals(2, sharedPostgresGrammarWorkers.corePoolSize)
        assertEquals(2, sharedPostgresGrammarWorkers.maximumPoolSize)
        assertEquals(30L, sharedPostgresGrammarWorkers.getKeepAliveTime(TimeUnit.SECONDS))
        assertIs<LinkedBlockingQueue<*>>(sharedPostgresGrammarWorkers.queue)
        assertTrue(sharedPostgresGrammarWorkers.allowsCoreThreadTimeOut())
        assertIs<ThreadPoolExecutor.AbortPolicy>(sharedPostgresGrammarWorkers.rejectedExecutionHandler)
        val worker = AtomicReference<Thread>()
        val pool = workers(keepAliveMillis = 20)
        assertSame(VerifyResult.Accepted, postgres().verifyWithParser("SQL", pool, 5_000, slots) {
            worker.set(Thread.currentThread())
            assertTrue(Thread.currentThread().isDaemon)
            assertTrue(Thread.currentThread().name.startsWith("brikk-postgres-grammar-"))
        })
        worker.get().join(5_000)
        assertFalse(worker.get().isAlive)
        assertEquals(0, pool.poolSize)
        assertFalse(pool.isShutdown)
    }

    @Test
    fun closingAnInstanceDoesNotOwnOrShutDownTheSharedWorkers() {
        val verifier = postgres()
        val pool = workers()
        verifier.close()
        assertSame(VerifyResult.Accepted, verifier.verifyWithParser("SQL", pool, 5_000, slots) {})
        assertFalse(pool.isShutdown)
        assertFalse(sharedPostgresGrammarWorkers.isShutdown)
    }

    @Test
    fun realPostgresParserAcceptsOrdinarySqlAndRejectsSyntaxThroughTheTimedPath() {
        val verifier = postgres()
        assertSame(VerifyResult.Accepted, verifier.verify("SELECT * FROM users WHERE id = 42"))
        assertIs<VerifyResult.Rejected>(verifier.verify("SELECT FROM WHERE"))
    }

    private fun postgres(): ShardingSphereVerifier = ShardingSphereVerifier("postgres", "PostgreSQL")
    private fun countSequentialBusyChecks(): Int {
        val verifier = postgres()
        var busy = 0
        repeat(20_000) {
            val result = verifier.verifyWithParser("SELECT 1", sharedPostgresGrammarWorkers, 5_000, sharedPostgresGrammarSlots) {}
            if (result is VerifyResult.Unverified && result.reason == "PostgreSQL grammar check is busy with earlier statements") {
                busy++
            } else {
                assertSame(VerifyResult.Accepted, result)
            }
        }
        return busy
    }
    private fun workers(keepAliveMillis: Long = 30_000): ThreadPoolExecutor =
        newPostgresGrammarWorkers(keepAliveMillis).also { executors += it }
    private fun callers(): ExecutorService = Executors.newSingleThreadExecutor().also { executors += it }
    private fun latch(): CountDownLatch = CountDownLatch(1).also { latches += it }
    private fun await(latch: CountDownLatch) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "test latch was not released")
    }
}
