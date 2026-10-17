package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How one harvest ended. */
public sealed interface HarvestOutcome {
    public data class Complete(val harvest: Harvest) : HarvestOutcome

    /** The harvester read an incomplete inventory. */
    public data object Incomplete : HarvestOutcome

    public data class Failed(val error: Exception) : HarvestOutcome

    /** The harvest did not end within its deadline; it was cancelled and its result is discarded. */
    public data object TimedOut : HarvestOutcome
}

/** The end of a harvest's time budget. */
internal class HarvestDeadline(val budget: Duration, timeSource: TimeSource = TimeSource.Monotonic) {
    private val end = timeSource.markNow() + budget

    val remaining: Duration get() = -end.elapsedNow()

    fun check() {
        if (end.hasPassedNow()) throw HarvestTimeoutException(budget)
    }

    /** The JDBC query timeout for what remains, at least one second. */
    val queryTimeoutSeconds: Int get() = ceil(remaining.inWholeMilliseconds / 1000.0).toInt().coerceAtLeast(1)
}

internal class HarvestTimeoutException(budget: Duration) : Exception("The harvest exceeded its $budget deadline")

/** Rows whose running statement can be cancelled from another thread. */
internal interface CancellableRows : CatalogRowSource {
    fun cancel()
}

/** How long a cancelled harvest may take to give its connection back. */
private val CLEANUP = 2.seconds

/**
 * Runs [harvester] over the rows [rows] opens, on a pooled thread, for at most [budget]. Every query
 * and row checks the deadline. Past it, or when the caller is cancelled, the running statement is
 * cancelled and closed and the harvest thread interrupted, and this returns once the harvest has let
 * go of the connection, waiting at most [CLEANUP]: a driver that ignores cancellation may keep it busy.
 */
internal suspend fun runHarvest(harvester: LiveCatalogHarvester, budget: Duration, rows: (HarvestDeadline) -> CatalogRowSource): HarvestOutcome {
    val running = Running.start(harvester, budget, rows)
    try {
        return withTimeoutOrNull(budget) { Read(running.result.await()) }?.let { outcomeOf(it.harvest) } ?: HarvestOutcome.TimedOut
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return failure(e)
    } finally {
        withContext(NonCancellable) {
            running.stop()
            withTimeoutOrNull(CLEANUP) { running.finished.await() }
        }
    }
}

/** [runHarvest] for a thread that must wait, such as a console's engine thread holding its connection. */
internal fun runHarvestBlocking(harvester: LiveCatalogHarvester, budget: Duration, rows: (HarvestDeadline) -> CatalogRowSource): HarvestOutcome {
    val running = Running.start(harvester, budget, rows)
    try {
        return outcomeOf(running.result.get(budget.inWholeMilliseconds, TimeUnit.MILLISECONDS))
    } catch (_: TimeoutException) {
        return HarvestOutcome.TimedOut
    } catch (e: ExecutionException) {
        return when (val cause = e.cause) {
            is Exception -> failure(cause)
            null -> HarvestOutcome.Failed(e)
            else -> throw cause
        }
    } finally {
        running.stop()
        try {
            running.finished.get(CLEANUP.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
        }
    }
}

private class Read(val harvest: Harvest?)

/** One harvest on a pooled thread: its [result], and [finished] once it no longer uses the connection. */
private class Running(
    private val rows: CatalogRowSource,
    val result: CompletableFuture<Harvest?>,
    private val task: Future<*>,
    private val state: AtomicInteger,
    val finished: CompletableFuture<Unit>,
) {
    /** Abandons a harvest that has not started; cancels the statement and interrupts one that runs. */
    fun stop() {
        if (state.compareAndSet(NEW, ABANDONED)) {
            finished.complete(Unit)
        } else if (!finished.isDone) {
            (rows as? CancellableRows)?.cancel()
            task.cancel(true)
        }
    }

    companion object {
        const val NEW = 0
        const val RUNNING = 1
        const val ABANDONED = 2

        fun start(harvester: LiveCatalogHarvester, budget: Duration, open: (HarvestDeadline) -> CatalogRowSource): Running {
            val deadline = HarvestDeadline(budget)
            val rows = open(deadline)
            val result = CompletableFuture<Harvest?>()
            val finished = CompletableFuture<Unit>()
            val state = AtomicInteger(NEW)
            val task = AppExecutorUtil.getAppExecutorService().submit {
                if (!state.compareAndSet(NEW, RUNNING)) return@submit
                try {
                    result.complete(harvester.harvest(GuardedRows(rows, deadline)))
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                } finally {
                    finished.complete(Unit)
                }
            }
            return Running(rows, result, task, state, finished)
        }
    }
}

private fun outcomeOf(harvest: Harvest?): HarvestOutcome = harvest?.let(HarvestOutcome::Complete) ?: HarvestOutcome.Incomplete

private fun failure(e: Exception): HarvestOutcome = if (e is HarvestTimeoutException) HarvestOutcome.TimedOut else HarvestOutcome.Failed(e)

/** Checks the deadline and interruption before each query and row. */
private class GuardedRows(private val rows: CatalogRowSource, private val deadline: HarvestDeadline) : CatalogRowSource {
    override fun forEachRow(sql: String, onRow: (CatalogRow) -> Unit) {
        check()
        rows.forEachRow(sql) { row ->
            check()
            onRow(row)
        }
    }

    private fun check() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("The harvest was cancelled")
        deadline.check()
    }
}

/**
 * Catalog rows over a Database Tools connection, whose JDBC objects live in the driver process. The
 * running statement is cancelled and closed by [cancel], since a driver may ignore query timeouts.
 */
internal class RemoteCatalogRows(private val connection: DatabaseConnectionCore, private val deadline: HarvestDeadline) : CancellableRows {
    @Volatile private var active: RemoteStatement? = null
    @Volatile private var cancelled = false

    override fun forEachRow(sql: String, onRow: (CatalogRow) -> Unit) {
        if (cancelled) throw InterruptedException("The harvest was cancelled")
        val statement = JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() } ?: error("No statement")
        active = statement
        try {
            if (cancelled) throw InterruptedException("The harvest was cancelled")
            JdbcNativeUtil.performSafe { statement.setQueryTimeout(deadline.queryTimeoutSeconds) }
            val rows = JdbcNativeUtil.computeRemote { statement.executeQuery(sql) } ?: error("No result for $sql")
            try {
                while (rows.next()) onRow(CatalogRow(rows::getString))
            } finally {
                JdbcNativeUtil.performSafe { rows.close() }
            }
        } finally {
            active = null
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }

    override fun cancel() {
        cancelled = true
        active?.let { statement ->
            JdbcNativeUtil.performSafe { statement.cancel() }
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }
}
