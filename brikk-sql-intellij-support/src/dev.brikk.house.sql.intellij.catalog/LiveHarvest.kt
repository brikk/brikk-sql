package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.ApiStatus

/** How one harvest ended. */
@ApiStatus.Experimental
public sealed interface HarvestOutcome {
    public data class Complete(val harvest: Harvest) : HarvestOutcome

    /** The harvester read an incomplete inventory. */
    public data object Incomplete : HarvestOutcome

    public data class Failed(val error: Exception) : HarvestOutcome

    /** The harvest did not end within its deadline; it was cancelled and its result is discarded. */
    public data object TimedOut : HarvestOutcome
}

/** The end of a harvest's time budget. */
@ApiStatus.Experimental
public class HarvestDeadline(public val budget: Duration, timeSource: TimeSource = TimeSource.Monotonic) {
    private val end = timeSource.markNow() + budget

    public val remaining: Duration get() = -end.elapsedNow()

    /** Throws [HarvestTimeoutException] once the budget is spent. */
    public fun check() {
        if (end.hasPassedNow()) throw HarvestTimeoutException(budget)
    }

    /** The JDBC query timeout for what remains, at least one second. */
    internal val queryTimeoutSeconds: Int get() = ceil(remaining.inWholeMilliseconds / 1000.0).toInt().coerceAtLeast(1)
}

@ApiStatus.Experimental
public class HarvestTimeoutException(budget: Duration) : Exception("The harvest exceeded its $budget deadline")

/**
 * Runs [harvester] over the rows [rows] opens, on a pooled thread, for at most [budget]. Every query
 * and row checks the deadline; past it, the harvest is cancelled and its result discarded even if a
 * driver call never returns. Cancelling the caller cancels the harvest.
 */
@ApiStatus.Experimental
public suspend fun runHarvest(harvester: LiveCatalogHarvester, budget: Duration, rows: (HarvestDeadline) -> CatalogRowSource): HarvestOutcome {
    val running = startHarvest(harvester, budget, rows)
    return try {
        withTimeoutOrNull(budget) { Read(running.result.await()) }?.let { outcomeOf(it.harvest) } ?: HarvestOutcome.TimedOut
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(e)
    } finally {
        running.task.cancel(true)
    }
}

private class Read(val harvest: Harvest?)

/** [runHarvest] for a thread that must wait, such as a console's engine thread holding its connection. */
@ApiStatus.Experimental
public fun runHarvestBlocking(harvester: LiveCatalogHarvester, budget: Duration, rows: (HarvestDeadline) -> CatalogRowSource): HarvestOutcome {
    val running = startHarvest(harvester, budget, rows)
    return try {
        outcomeOf(running.result.get(budget.inWholeMilliseconds, TimeUnit.MILLISECONDS))
    } catch (_: TimeoutException) {
        HarvestOutcome.TimedOut
    } catch (e: ExecutionException) {
        when (val cause = e.cause) {
            is Exception -> failure(cause)
            null -> HarvestOutcome.Failed(e)
            else -> throw cause
        }
    } finally {
        running.task.cancel(true)
    }
}

/** A harvest's [result], and the [task] whose cancellation interrupts its thread. */
private class Running(val result: CompletableFuture<Harvest?>, val task: Future<*>)

private fun startHarvest(harvester: LiveCatalogHarvester, budget: Duration, rows: (HarvestDeadline) -> CatalogRowSource): Running {
    val deadline = HarvestDeadline(budget)
    val result = CompletableFuture<Harvest?>()
    val task = AppExecutorUtil.getAppExecutorService().submit {
        try {
            result.complete(harvester.harvest(GuardedRows(rows(deadline), deadline)))
        } catch (e: Throwable) {
            result.completeExceptionally(e)
        }
    }
    return Running(result, task)
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

/** Catalog rows over a plain JDBC [connection], such as an embedded engine; the query timeout follows [deadline]. */
@ApiStatus.Experimental
public class JdbcCatalogRows(private val connection: Connection, private val deadline: HarvestDeadline? = null) : CatalogRowSource {
    override fun forEachRow(sql: String, onRow: (CatalogRow) -> Unit) {
        connection.createStatement().use { statement ->
            deadline?.let { runCatching { statement.queryTimeout = it.queryTimeoutSeconds } }
            statement.executeQuery(sql).use { rows ->
                while (rows.next()) onRow(CatalogRow(rows::getString))
            }
        }
    }
}

/** Catalog rows over a Database Tools connection, whose JDBC objects live in the driver process. */
internal class RemoteCatalogRows(private val connection: DatabaseConnectionCore, private val deadline: HarvestDeadline) : CatalogRowSource {
    override fun forEachRow(sql: String, onRow: (CatalogRow) -> Unit) {
        val statement = JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() } ?: error("No statement")
        try {
            // Drivers without query timeouts skip it; the deadline still holds.
            JdbcNativeUtil.performSafe { statement.setQueryTimeout(deadline.queryTimeoutSeconds) }
            val rows = JdbcNativeUtil.computeRemote { statement.executeQuery(sql) } ?: error("No result for $sql")
            try {
                while (rows.next()) onRow(CatalogRow(rows::getString))
            } finally {
                JdbcNativeUtil.performSafe { rows.close() }
            }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }
}
