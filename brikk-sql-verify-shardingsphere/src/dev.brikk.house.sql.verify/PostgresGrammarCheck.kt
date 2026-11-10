package dev.brikk.house.sql.verify

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal const val POSTGRES_GRAMMAR_CHECK_TIMEOUT_MILLIS: Long = 2_000

private val postgresWorkerNumber = AtomicInteger()
internal val sharedPostgresGrammarSlots: Semaphore = Semaphore(2)
internal val sharedPostgresGrammarWorkers: ThreadPoolExecutor = newPostgresGrammarWorkers()

// Slots admit at most two tasks. The queue bridges the handoff after a parse returns.
internal fun newPostgresGrammarWorkers(keepAliveMillis: Long = 30_000): ThreadPoolExecutor =
    ThreadPoolExecutor(2, 2, keepAliveMillis, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), { action ->
        Thread(action, "brikk-postgres-grammar-${postgresWorkerNumber.incrementAndGet()}").apply {
            isDaemon = true
            contextClassLoader = ShardingSphereVerifier::class.java.classLoader
        }
    }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

internal fun postgresGrammarCheck(
    executor: ExecutorService,
    timeoutMillis: Long,
    slots: Semaphore,
    parse: () -> VerifyResult,
): VerifyResult {
    if (!slots.tryAcquire()) {
        return VerifyResult.Unverified("PostgreSQL grammar check is busy with earlier statements")
    }
    val result = try {
        executor.submit(Callable {
            try {
                parse()
            } finally {
                slots.release()
            }
        })
    } catch (e: Throwable) {
        slots.release()
        throw e
    }
    return try {
        result.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        // Do not cancel: the parser ignores interruption. Its slot stays occupied until the parse returns.
        VerifyResult.Unverified("PostgreSQL grammar check took too long")
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CancellationException("PostgreSQL verification was interrupted").apply { initCause(e) }
    }
}
