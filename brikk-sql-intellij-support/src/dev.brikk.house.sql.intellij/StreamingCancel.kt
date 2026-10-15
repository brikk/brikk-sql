package dev.brikk.house.sql.intellij

import com.intellij.database.DataBus
import com.intellij.database.actions.CancelRunningStatementsAction
import com.intellij.database.console.JdbcEngine
import com.intellij.database.console.client.SessionClient
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.datagrid.DataProducer
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.annotations.ApiStatus

/**
 * Claims Cancel for a console of exactly [dataSources]' dbms (not the aliases it also accepts) whose
 * request is streaming its results, and leaves every other press to the wrapped action. Once results
 * stream, the stock cancel only stops fetching at the next chunk and closes the statement; a driver may
 * then read the rest of the results while the server keeps running the query (ClickHouse JDBC 0.10),
 * and its `cancel` does nothing once the statement is closed. The platform no longer holds the
 * streaming statement where a cancel can reach it, so this cancels the statements of the console
 * engine's own connection (`RemoteConnection.cancelAll`) and only then sends the stock cancel request,
 * whose cleanup can no longer close the statement before it is cancelled. Both steps happen only while
 * the engine still runs the captured request on that connection, and the engine starts no next request
 * before they are done: a connection cancel still running after [deadline] disconnects the console
 * instead, so the next request runs on a new connection. [worker] runs the steps off the EDT.
 */
@ApiStatus.Experimental
public class StreamingCancelInterceptor(
    private val dataSources: DialectDataSourceResolver,
    private val worker: Executor = Executor { ApplicationManager.getApplication().executeOnPooledThread(it) },
    private val deadline: Duration = 30.seconds,
) : ActionInterceptor {
    // Binary compatibility: the constructor before deadline.
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public constructor(
        dataSources: DialectDataSourceResolver,
        worker: Executor = Executor { ApplicationManager.getApplication().executeOnPooledThread(it) },
    ) : this(dataSources, worker, 30.seconds)

    override fun perform(event: AnActionEvent, delegate: () -> Unit): ExecutionClaim {
        val session = DatabaseSessionManager.findSession(event) ?: return ExecutionClaim.DECLINED
        // A second press on a cancelled session is the stock offer to deactivate the data source.
        // Exactly the configured dbms, not its registry aliases: another plugin's or the stock dbms keeps its own cancel.
        if (session.isCancelled || session.connectionPoint.dataSource.dbms.name != dataSources.dbms.name) return ExecutionClaim.DECLINED
        val request = StreamingRequest.of(session, deadline) ?: return ExecutionClaim.DECLINED
        try {
            worker.execute(request::cancel)
        } catch (e: RejectedExecutionException) {
            // Nothing started: the stock cancel runs instead.
            request.release()
            LOG.warn("The streaming cancel could not start", e)
            return ExecutionClaim.DECLINED
        } catch (e: Throwable) {
            request.release()
            throw e
        }
        return ExecutionClaim.HANDLED
    }
}

/** Wraps Cancel with [StreamingCancelInterceptor]. Register a no-arg subclass as `dynamicActionConfigurationCustomizer`. */
@ApiStatus.Experimental
public abstract class StreamingCancelConfiguration(dataSources: DialectDataSourceResolver) : CooperativeActionConfiguration() {
    override val actionIds: List<String> = listOf(CANCEL)
    override val interceptor: ActionInterceptor = StreamingCancelInterceptor(dataSources)
}

/** [session]'s request [context], streaming on [engine] over its own [connection], as captured when Cancel was pressed. */
private class StreamingRequest(
    private val session: DatabaseSession,
    private val engine: JdbcEngine,
    private val context: DataRequest.Context,
    private val connection: DatabaseConnection,
    private val deadline: Duration,
) {
    @Volatile private var ended = false
    private val done = CountDownLatch(1)
    @Volatile private var sent: Pair<DataRequest, CountDownLatch>? = null
    private val watch = Disposer.newDisposable("streaming cancel")

    init {
        // The engine runs its requests one after another and reports a finished request on its own thread before
        // it starts the next: holding that report until the cancel is done keeps the next request from opening a
        // statement while the connection's statements are being cancelled, or from meeting the stock cleanup.
        DataBus.addRootAuditor(session.project, object : DataAuditor {
            override fun requestFinished(context: DataRequest.Context) {
                if (context !== this@StreamingRequest.context) return
                ended = true
                uninterruptibly { done.await() }
            }

            // The engine's own report that it handled a request (its promise completes later, on the EDT).
            override fun jobFinished(request: DataRequest, producer: DataProducer) {
                sent?.takeIf { it.first === request }?.second?.countDown()
            }
        }, watch)
    }

    /**
     * Cancels the statements of the engine's connection, then sends the stock cancel request and waits until it is
     * handled, each only while the engine still runs the captured request (a script's request goes on with its
     * next statement). A connection cancel still running after the deadline disconnects the console: the next
     * request then gets a new connection, which the cancel cannot reach. The engine starts no other request
     * before this returns.
     */
    fun cancel() {
        try {
            if (!isCurrent()) return
            val cancelling = ApplicationManager.getApplication().executeOnPooledThread(Callable { connection.remoteConnection.cancelAll() })
            if (uninterruptibly { finishes(cancelling) }) {
                if (isCurrent()) awaitHandled(CancelRunningStatementsAction.createCancelRequest(session) as DataRequest, "stock cancel")
            } else {
                LOG.warn("Cancelling the statements of a streaming request took over $deadline; disconnecting its console")
                awaitHandled(DataRequest.Disconnect(session), "disconnect")
                // Without a new connection for the next request, the cancel must end first.
                if (engine.runsOn(connection)) uninterruptibly { finishes(cancelling, wait = true) }
            }
        } finally {
            release()
        }
    }

    /** Lets the engine go on, and stops watching for the captured request. */
    fun release() {
        done.countDown()
        Disposer.dispose(watch)
    }

    /** Whether [cancelling] ended within the deadline (or at all, with [wait]); its failure is logged. */
    private fun finishes(cancelling: Future<*>, wait: Boolean = false): Boolean = try {
        if (wait) cancelling.get() else cancelling.get(deadline.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        true
    } catch (_: TimeoutException) {
        false
    } catch (e: ExecutionException) {
        LOG.warn("Cancelling a streaming request failed", e.cause)
        true
    }

    /**
     * Sends [request] to the session's engine and waits until the engine has handled it, however long that takes,
     * or until the engine is disposed (it then runs no next request).
     */
    private fun awaitHandled(request: DataRequest, what: String) {
        val handled = CountDownLatch(1)
        sent = request to handled
        session.messageBus.dataProducer.processRequest(request)
        uninterruptibly {
            while (!handled.await(deadline.inWholeMilliseconds, TimeUnit.MILLISECONDS) && !engine.isDisposed) {
                LOG.warn("The $what of a streaming cancel is not handled after $deadline; still waiting")
            }
        }
    }

    private fun isCurrent(): Boolean = !ended && engine.requestContextIfAny === context && engine.runsOn(connection)

    companion object {
        /** [session]'s request if its console engine is streaming results over its own ready connection. */
        fun of(session: DatabaseSession, deadline: Duration): StreamingRequest? =
            DatabaseConnectionManager.getInstance().activeConnections.firstNotNullOfOrNull { connection ->
                val engine = connection.requestor as? JdbcEngine ?: return@firstNotNullOfOrNull null
                val context = engine.requestContextIfAny?.takeIf { (it.request.owner as? SessionClient<*>)?.session === session }
                    ?: return@firstNotNullOfOrNull null
                if (!engine.runsOn(connection) || !engine.isStreaming()) return@firstNotNullOfOrNull null
                StreamingRequest(session, engine, context, connection, deadline)
            }
    }
}

/** Runs [wait] to its end through interrupts, which it passes on afterwards. */
private fun <T> uninterruptibly(wait: () -> T): T {
    var interrupted = false
    try {
        while (true) {
            try {
                return wait()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

private const val STREAMING = "STREAMING"

// Database Tools keeps an engine's execution state private, with no public alternative (see the README):
// read through the field's declared shape, else the stock cancel runs.
private val stateField: Field? by lazy {
    JdbcEngine::class.java.declaredFields.filter { field ->
        val state = (field.genericType as? ParameterizedType)?.actualTypeArguments?.singleOrNull() as? Class<*>
        field.type == AtomicReference::class.java && state != null && state.isEnum && state.enumConstants.any { (it as Enum<*>).name == STREAMING }
    }.singleOrNull()?.takeIf { it.trySetAccessible() }.also { if (it == null) LOG.warn("JdbcEngine has no single execution state field of the expected shape") }
}

/** Whether the engine's ready connection is [connection] (the engine hands out a view of it per department). */
private fun JdbcEngine.runsOn(connection: DatabaseConnection): Boolean = currentConnectionIfReady?.remoteConnection === connection.remoteConnection

private fun JdbcEngine.isStreaming(): Boolean = ((stateField?.get(this) as? AtomicReference<*>)?.get() as? Enum<*>)?.name == STREAMING

private val LOG = logger<StreamingCancelInterceptor>()
