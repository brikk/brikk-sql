package dev.brikk.house.sql.verify

import dev.brikk.house.chdb.ChdbOutputFormat
import dev.brikk.house.chdb.ChdbResult
import dev.brikk.house.chdb.ChdbSession
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class ClickhouseVerifierLifecycleTest {
    @Test
    fun closeWaitsForVerificationToReleaseTheNativeSessionWithoutLoadingNativeCode() {
        val querying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val queries = AtomicInteger()
        val closes = AtomicInteger()
        val session = object : ChdbSession {
            override fun query(sql: String, format: ChdbOutputFormat): ChdbResult {
                queries.incrementAndGet()
                check(!closed.get()) { "chDB session is closed" }
                querying.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                assertFalse(closed.get(), "close interleaved with verification")
                return ChdbResult(byteArrayOf(), 0, 0, 0.0)
            }
            override fun close() { closes.incrementAndGet(); closed.set(true) }
        }
        val verifier = ClickhouseVerifier::class.java.getDeclaredConstructor(ChdbSession::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(session, null)
        val result = CompletableFuture.supplyAsync { verifier.verify("SELECT 1") }
        assertTrue(querying.await(5, TimeUnit.SECONDS))
        val closing = CountDownLatch(1)
        val closeResult = CompletableFuture<Unit>()
        val closer = thread {
            closing.countDown()
            runCatching { verifier.close() }.fold(closeResult::complete, closeResult::completeExceptionally)
        }
        try {
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val threads = ManagementFactory.getThreadMXBean()
            fun blockedOnVerifier(): Boolean {
                val info = threads.getThreadInfo(closer.id) ?: return false
                return info.threadState == Thread.State.BLOCKED &&
                    info.lockInfo?.className == verifier.javaClass.name &&
                    info.lockInfo?.identityHashCode == System.identityHashCode(verifier)
            }
            while (!blockedOnVerifier() && !closeResult.isDone && System.nanoTime() < deadline) Thread.yield()
            assertTrue(blockedOnVerifier(), "close() did not block on the verifier monitor within five seconds (state=${closer.state})")
            assertFalse(closed.get())
        } finally {
            release.countDown()
        }
        assertIs<VerifyResult.Accepted>(result.get(5, TimeUnit.SECONDS))
        closeResult.get(5, TimeUnit.SECONDS)
        assertTrue(closed.get())
        val unavailable = assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 1"))
        assertTrue(unavailable.reason.contains("closed"))
        assertEquals(unavailable, verifier.verifyExpression("1"))
        verifier.close()
        assertEquals(1, queries.get(), "no query reaches the closed session")
        assertEquals(1, closes.get(), "closing twice closes the session only once")
    }

    @Test
    fun closingAnUnavailableVerifierKeepsItsOriginalReason() {
        val verifier = ClickhouseVerifier::class.java.getDeclaredConstructor(ChdbSession::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(null, "no native library for this host")
        val unavailable = assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 1"))
        verifier.close()
        verifier.close()
        assertEquals("no native library for this host", unavailable.reason)
        assertEquals(unavailable, verifier.verify("SELECT 1"))
        assertEquals(unavailable, verifier.verifyExpression("1"))
    }
}
