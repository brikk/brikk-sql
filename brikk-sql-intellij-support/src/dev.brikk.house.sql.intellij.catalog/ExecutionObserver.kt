package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.DataBus
import com.intellij.database.Dbms
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.client.SessionClient
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.util.ObjectPath
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus

/**
 * Which executed statements a dialect watches and what follows them. The minimum is [dbms] and
 * [statements]; with neither service class set, nothing follows.
 */
@ApiStatus.Experimental
public interface ExecutionObserverProfile {
    public val dbms: Dbms

    /** Which data sources' consoles are watched: those whose dbms it accepts. */
    public val dataSources: DialectDataSourceResolver get() = DialectDataSourceResolver(dbms)

    public val statements: StatementHeadDetector

    /** The live catalog that harvests again after a statement changes the function inventory. */
    public val liveCatalog: Class<out LiveCatalogService>? get() = null

    /** The auto-introspection service that reloads a namespace after a statement changes it. */
    public val autoIntrospect: Class<out AutoIntrospectService>? get() = null

    /** How long a namespace reload waits for more statements changing the same namespace. */
    public val namespaceRefreshDelay: Duration get() = 2.seconds
}

/** Statements that ran in [session] on [dataSource]; [requestor] opened the session's connection. */
@ApiStatus.Experimental
public data class ExecutedStatements(
    val session: DatabaseSession,
    val dataSource: LocalDataSource,
    val requestor: Any?,
    val statements: List<DetectedStatement>,
) {
    /** Whether a change may belong to the session alone. */
    val sessionFacts: Boolean get() = statements.any { (it.effect as? StatementEffect.FunctionInventory)?.sessionFacts == true }

    /** Whether a matched rule asks for the refresh's result to be shown. */
    val notifies: Boolean get() = statements.any { it.rule.notifies }
}

/** A namespace of [dataSource] to reload: [path] from the top level, empty for the top-level list itself. */
@ApiStatus.Experimental
public data class NamespaceChange(val dataSource: LocalDataSource, val path: List<String>)

/**
 * Watches every request that finishes in the project's consoles on the profile's dbms and passes the
 * statements its [ExecutionObserverProfile.statements] detect to [onFunctionInventoryChange] and
 * [onNamespaceChange]. By default the first harvests the live catalog again over the session's own
 * connection, and the second reloads the namespace, debounced, through the auto-introspection service.
 *
 * Register a project service subclass with the platform's `(Project, CoroutineScope)` constructor, and
 * an [ExecutionObserverStartup] subclass naming it as `postStartupActivity`. Statements are observed
 * after they ran, on the session's engine thread, from the project's root data bus (one subscription
 * sees every session); extensions loaded implicitly (DuckDB autoload) run no statement and are not seen.
 */
@ApiStatus.Experimental
public abstract class ExecutionObserver(protected val project: Project, private val scope: CoroutineScope) : DataAuditor, Disposable {
    public abstract val profile: ExecutionObserverProfile

    private val started = AtomicBoolean()
    private val debouncer by lazy { RefreshDebouncer(scope, profile.namespaceRefreshDelay) }
    private val dataSources by lazy { profile.dataSources }

    /** Subscribes to the project's root data bus, once, until the service is disposed. */
    public fun start() {
        if (started.compareAndSet(false, true)) DataBus.addRootAuditor(project, this, this)
    }

    override fun requestFinished(context: DataRequest.Context) {
        try {
            observe(context)
        } catch (e: Throwable) {
            // Never fail the console's request, whatever a plugin's harvester throws.
            if (e is ControlFlowException || e is CancellationException || e is VirtualMachineError) throw e
            LOG.warn("Observing an executed request failed", e)
        }
    }

    private fun observe(context: DataRequest.Context) {
        val client = context.request.owner as? SessionClient<*> ?: return
        val session = client.session as? DatabaseSession ?: return
        val dataSource = session.connectionPoint.dataSource
        if (!dataSources.accepts(dataSource.dbms)) return
        val requestor: Any? = context.producer
        val liveCatalog = profile.liveCatalog?.let { ApplicationManager.getApplication().getService(it) }
        requestor?.let { liveCatalog?.learnSession(session, it) }
        val text = (context.request as? DataRequest.QueryRequest)?.query ?: context.query
        val detected = profile.statements.detect(text)
        if (detected.isEmpty()) return
        val executed = ExecutedStatements(session, dataSource, requestor, detected)
        if (detected.any { it.effect is StatementEffect.FunctionInventory }) onFunctionInventoryChange(executed)
        val current by lazy { (client as? JdbcConsole)?.currentNamespace?.let(::namesOf).orEmpty() }
        detected.mapNotNull { statement ->
            val effect = statement.effect as? StatementEffect.Namespace ?: return@mapNotNull null
            val name = statement.name.takeLast(effect.depth)
            val absolute = current.take(effect.depth - name.size) + name
            // A reload below the top level needs the whole name, written or completed from the console's namespace.
            absolute.takeIf { effect.refreshDepth == 0 || it.size == effect.depth }?.take(effect.refreshDepth)
        }.distinct().forEach { onNamespaceChange(NamespaceChange(dataSource, it)) }
    }

    /** Harvests the live catalog again ([LiveCatalogService.inventoryChanged]). */
    protected open fun onFunctionInventoryChange(executed: ExecutedStatements) {
        val liveCatalog = profile.liveCatalog?.let { ApplicationManager.getApplication().getService(it) } ?: return
        liveCatalog.inventoryChanged(project, executed.session, executed.requestor, executed.sessionFacts, executed.notifies)
    }

    /** Reloads [change]'s namespace through the auto-introspection service, after the profile's quiet delay. */
    protected open fun onNamespaceChange(change: NamespaceChange) {
        val service = profile.autoIntrospect?.let(project::getService) ?: return
        debouncer.submit(change.dataSource.uniqueId to change.path) { service.refreshChanged(change.dataSource, change.path) }
    }

    override fun dispose() {}

    private fun namesOf(path: ObjectPath): List<String> =
        generateSequence(path) { it.parent }.mapNotNull { it.name?.takeIf(String::isNotBlank) }.toList().asReversed()

    private companion object {
        val LOG = logger<ExecutionObserver>()
    }
}

/** `postStartupActivity` that starts the plugin's [ExecutionObserver]. Register a no-arg subclass naming its service class. */
@ApiStatus.Experimental
public abstract class ExecutionObserverStartup(private val service: Class<out ExecutionObserver>) : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(service)?.start()
    }
}
