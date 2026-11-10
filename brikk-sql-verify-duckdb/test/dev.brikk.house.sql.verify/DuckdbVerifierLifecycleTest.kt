package dev.brikk.house.sql.verify

import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DuckdbVerifierLifecycleTest {
    @Test
    fun closeWaitsForVerificationToReleaseTheSharedConnection() {
        val querying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "createStatement" -> {
                    querying.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    assertFalse(closed.get(), "close interleaved with verification")
                    throw SQLException("fake parse channel stopped")
                }
                "close" -> closed.set(true)
                else -> error("Unexpected ${method.name}")
            }
        } as Connection
        val verifier = DuckdbVerifier { connection }
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
        assertIs<VerifyResult.Unavailable>(result.get(5, TimeUnit.SECONDS))
        closeResult.get(5, TimeUnit.SECONDS)
        assertTrue(closed.get())
    }

    @Test
    fun theCorpusVerifierReopensAfterCloseButTheParseOnlyVerifierStaysUnavailable() {
        DuckdbVerifier().use { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1"))
            verifier.close()
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1"))
        }
        ParseOnlyDuckdbVerifier().use { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT 1"))
            verifier.close()
            assertIs<VerifyResult.Unavailable>(verifier.verify("SELECT 1"))
        }
    }
}
