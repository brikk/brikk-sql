package dev.brikk.house.sql.intellij

import com.intellij.database.actions.CancelRunningStatementsAction
import com.intellij.database.console.JdbcEngine
import com.intellij.database.console.client.SessionClient
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.datagrid.DataRequest
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.annotations.ApiStatus

/**
 * Claims Cancel for a console of [dataSources]' dbms whose request is streaming its results, and leaves
 * every other press to the wrapped action. Once results stream, the stock cancel only stops fetching at
 * the next chunk and closes the statement; a driver may then read the rest of the results while the
 * server keeps running the query (ClickHouse JDBC 0.10), and its `cancel` does nothing once the
 * statement is closed. The platform no longer holds the streaming statement where a cancel can reach it,
 * so this cancels the statements of the console engine's own connection (`RemoteConnection.cancelAll`),
 * off the EDT, and only then sends the stock cancel request, whose cleanup can no longer close the
 * statement before it is cancelled. Both steps happen only while the engine still runs the captured
 * request on that connection: once the request has ended, its successor is left alone. [worker] runs the
 * steps off the EDT.
 */
@ApiStatus.Experimental
public class StreamingCancelInterceptor(
    private val dataSources: DialectDataSourceResolver,
    private val worker: Executor = Executor { ApplicationManager.getApplication().executeOnPooledThread(it) },
) : ActionInterceptor {
    override fun perform(event: AnActionEvent, delegate: () -> Unit): ExecutionClaim {
        val session = DatabaseSessionManager.findSession(event) ?: return ExecutionClaim.DECLINED
        // A second press on a cancelled session is the stock offer to deactivate the data source.
        if (session.isCancelled || !dataSources.accepts(session.connectionPoint.dataSource.dbms)) return ExecutionClaim.DECLINED
        val request = StreamingRequest.of(session) ?: return ExecutionClaim.DECLINED
        worker.execute(request::cancel)
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
) {
    /**
     * Cancels the statements of the engine's connection, then sends the stock cancel request, each only while
     * the engine still runs the captured request (a script's request goes on with its next statement).
     */
    fun cancel() {
        if (!isCurrent()) return
        try {
            connection.remoteConnection.cancelAll()
        } catch (e: Exception) {
            if (e is ControlFlowException || e is CancellationException) throw e
            LOG.warn("Cancelling a streaming request failed", e)
        } finally {
            if (isCurrent()) session.messageBus.dataProducer.processRequest(CancelRunningStatementsAction.createCancelRequest(session))
        }
    }

    private fun isCurrent(): Boolean = engine.requestContextIfAny === context && engine.runsOn(connection)

    companion object {
        /** [session]'s request if its console engine is streaming results over its own ready connection. */
        fun of(session: DatabaseSession): StreamingRequest? = DatabaseConnectionManager.getInstance().activeConnections.firstNotNullOfOrNull { connection ->
            val engine = connection.requestor as? JdbcEngine ?: return@firstNotNullOfOrNull null
            val context = engine.requestContextIfAny?.takeIf { (it.request.owner as? SessionClient<*>)?.session === session }
                ?: return@firstNotNullOfOrNull null
            if (!engine.runsOn(connection) || !engine.isStreaming()) return@firstNotNullOfOrNull null
            StreamingRequest(session, engine, context, connection)
        }
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
