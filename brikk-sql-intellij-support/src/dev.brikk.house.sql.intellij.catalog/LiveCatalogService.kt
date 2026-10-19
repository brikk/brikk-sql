package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.datagrid.DataProducer
import com.intellij.database.datagrid.DataRequest
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.sql.dialects.SqlLanguageDialect
import dev.brikk.house.sql.intellij.completion.completableFunctions
import dev.brikk.house.sql.intellij.dialect.SqlDialectEntry
import dev.brikk.house.sql.intellij.dialect.SqlDialectRegistry
import dev.brikk.house.sql.intellij.editorShowsFile
import dev.brikk.house.sql.intellij.notifyEscaped
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.MetadataSnapshot
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which connections harvest. A refresh always does. */
public enum class HarvestTrigger {
    /** The first connection of a connection identity in an IDE session; later ones do not. */
    ONCE_PER_SESSION,

    /**
     * Every console connection, whose session facts start over with it; other connections only while
     * the identity has no live harvest this IDE session.
     */
    EVERY_CONSOLE_CONNECT,

    /** No connection: only refreshes. */
    ON_REFRESH,
}

/**
 * A dialect's live function catalog. The minimum is [dbms], [productLabel], [metadata] and
 * [harvester]; the rest has defaults matching a names-and-signatures harvest kept in memory.
 */
public interface LiveCatalogProfile {
    public val dbms: Dbms

    /** The product name in user-visible text. */
    public val productLabel: String

    /** The bundled snapshots: the fallback, and the grammar forms live functions are presented with. */
    public val metadata: DialectMetadata

    public val harvester: LiveCatalogHarvester

    /** Whether anything harvests. A dialect whose harvest is optional turns it on here. */
    public val enabled: Boolean get() = true

    public val trigger: HarvestTrigger get() = HarvestTrigger.ONCE_PER_SESSION

    public val mergePolicy: LiveMergePolicy get() = LiveMergePolicy.REPLACE

    /** The directory name, unique to the plugin, harvests persist under; null keeps them in memory only. */
    public val persistenceId: String? get() = null

    /** The longest one harvest may take. */
    public val deadline: Duration get() = 5.seconds

    /** Persisted harvests not written for this long are deleted. */
    public val maxAge: Duration get() = 90.days

    /** How long a refresh requested after executed statements waits for more of them. */
    public val refreshDelay: Duration get() = 2.seconds

    /** Files of an earlier data-source-keyed format, migrated on first use. */
    public val legacyFiles: LegacyCatalogFiles? get() = null

    /** The notification group refresh results are shown in; null shows none. */
    public val notificationGroupId: String? get() = null

    /** Which data source serves a file. Override with a stored instance to set the resolver's options. */
    public val dataSources: DialectDataSourceResolver get() = DialectDataSourceResolver(dbms)

    /** Which server [dataSource] reaches: by default its dbms, URL and user. */
    public fun identity(dataSource: LocalDataSource): ConnectionIdentity =
        ConnectionIdentity.of(listOf(dbms.name, dataSource.url, dataSource.username))

    /**
     * Whether the engine has client transactions. With them (the default), a session the kit sees in a
     * transaction (manual commit, a begin statement, a connection not in auto-commit mode or of unknown
     * mode) is never harvested, since a failing harvest query could abort it. An engine without them
     * (ClickHouse) is never in one, so nothing about transactions stops a harvest.
     */
    public val clientTransactions: Boolean get() = true

    /**
     * Driver properties the refresh's helper connection to [dataSource] sets over the data source's own
     * and its URL's ([HelperConnectionPoint]), such as turning off a driver's response compression; the
     * data source is not changed.
     */
    public fun connectionProperties(dataSource: LocalDataSource): Map<String, String> = emptyMap()

    /** The functions offered without a harvest. */
    public fun baseline(snapshot: MetadataSnapshot): List<FunctionPresentation> = snapshot.completableFunctions()
}

/** Why a refresh ran. */
public sealed interface RefreshReason {
    /** The user asked for it. */
    public data object Manual : RefreshReason

    /** Executed statements changed the function inventory; [notify] when a matched rule asks for the result to be shown. */
    public data class Executed(val notify: Boolean) : RefreshReason
}

/** How a refresh ended. */
public sealed interface RefreshOutcome {
    /** The server's [inventory], and the facts of the [session] whose connection was harvested (none over a helper connection). */
    public data class Refreshed(val inventory: LiveInventory, val session: SessionFacts = SessionFacts.NONE) : RefreshOutcome

    /** The profile is not enabled. */
    public data object Disabled : RefreshOutcome

    public data class ConnectFailed(val detail: String?) : RefreshOutcome

    /** Connected, but the harvest failed, timed out or read an incomplete inventory; the previous catalog stays. */
    public data class HarvestFailed(val outcome: HarvestOutcome) : RefreshOutcome

    /** The data source's connection identity changed while the harvest ran; nothing was stored. */
    public data object Superseded : RefreshOutcome

    /** The session is in a transaction, which a failing harvest query could abort; nothing was harvested. */
    public data object SessionInTransaction : RefreshOutcome
}

/**
 * The live function catalog of one plugin's dialect: harvests on connect ([HarvestConnectionInterceptor]),
 * on refresh ([refresh], [RefreshLiveCatalogAction]) and after executed statements ([inventoryChanged]),
 * stores them in a [LiveCatalogStore] and answers completion ([completion]).
 *
 * Register an application service subclass, one per plugin, with the platform's `(CoroutineScope)`
 * constructor. Harvests on a console's own connection also read that session's facts; a harvest on any
 * other connection, a refresh's helper connection included, updates only the shared inventory.
 */
public abstract class LiveCatalogService(protected val scope: CoroutineScope) {
    public abstract val profile: LiveCatalogProfile

    /** Where harvests persist; tests override it. */
    protected open fun persistenceDirectory(): Path? =
        profile.persistenceId?.let { Path.of(PathManager.getSystemPath(), "brikk-live-catalog", it) }

    internal val store: LiveCatalogStore by lazy {
        val profile = profile
        LiveCatalogStore(persistenceDirectory(), profile.legacyFiles).also { store ->
            scope.launch(Dispatchers.IO) { store.sweep(profile.maxAge) }
        }
    }

    private val dataSources by lazy { profile.dataSources }
    private val debouncer by lazy { RefreshDebouncer(scope, profile.refreshDelay) }
    private val inFlight = ConcurrentHashMap.newKeySet<Pair<String, ConnectionIdentity>>()

    /** A console connection and the identity of the server it reached. */
    private class ConsoleConnection(connection: DatabaseConnectionCore, val identity: ConnectionIdentity) {
        private val reference = WeakReference(connection)
        val connection: DatabaseConnectionCore? get() = reference.get()
    }

    /**
     * The latest connection of each console engine (its connection requestor). Values hold the connection
     * weakly too: it references its requestor, and an engine its session, which would keep the keys alive.
     */
    private val consoleConnections: MutableMap<Any, ConsoleConnection> = Collections.synchronizedMap(WeakHashMap())

    /** Sessions in a transaction a statement began, which the platform does not track. */
    private val explicitTransactions: MutableSet<DatabaseSession> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** The connection requestor of each session that executed something, which keys its facts. */
    private val sessionRequestors: MutableMap<DatabaseSession, WeakReference<Any>> = Collections.synchronizedMap(WeakHashMap())

    private val completions: MutableMap<CompletionKey, LiveCompletion> = Collections.synchronizedMap(object : LinkedHashMap<CompletionKey, LiveCompletion>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CompletionKey, LiveCompletion>?) = size > 16
    })

    /** The catalog of [dataSource] by the store's selection rule, with [session]'s facts. */
    public fun selection(dataSource: LocalDataSource, session: DatabaseSession? = null): LiveCatalogSelection {
        if (!profile.enabled) return LiveCatalogSelection.BUNDLED
        return store.select(dataSource.uniqueId, profile.identity(dataSource), session?.let { sessionRequestors[it]?.get() })
    }

    /**
     * What completion offers in [file]: the selection for its data source and console session, merged with
     * the bundled functions. A console's session facts are served once the session ran a request the
     * [ExecutionObserver] saw, which links the session to its connection.
     */
    public fun completion(file: PsiFile): LiveCompletion {
        val profile = profile
        val dataSource = dataSources.resolve(file).dataSource
        val selection = dataSource?.let { selection(it, dataSources.consoleSession(file)) } ?: LiveCatalogSelection.BUNDLED
        val version = selection.inventory?.serverVersion ?: dataSource?.databaseVersion?.version
        val snapshot = profile.metadata.forServerVersion(version).snapshot
        val baseline = profile.baseline(snapshot)
        val key = CompletionKey(selection.inventory, selection.session, snapshot, baseline)
        return completions[key] ?: profile.mergePolicy.merge(selection, snapshot, baseline).also { completions[key] = it }
    }

    internal fun dataSourceOf(file: PsiFile): LocalDataSource? = dataSources.resolve(file).dataSource

    /** Which data source serves [file], and why. */
    internal fun resolutionOf(file: PsiFile): DataSourceResolution = dataSources.resolve(file)

    /** The dialect the resolver's dbms speaks, if [SqlDialectRegistry] knows it. */
    internal val dialect: SqlDialectEntry? get() = SqlDialectRegistry.forDbms(dataSources.dbms)

    internal fun consoleSessionOf(file: PsiFile): DatabaseSession? = dataSources.consoleSession(file)

    /** Whether connections of [dbms] are this profile's. */
    internal fun accepts(dbms: Dbms): Boolean = profile.enabled && dataSources.accepts(dbms)

    /** Harvests over [connection] if the profile's trigger asks for it; called by [HarvestConnectionInterceptor]. */
    internal suspend fun connected(connection: DatabaseConnectionCore) {
        val profile = profile
        if (!accepts(connection.dbms)) return
        val requestor = (connection as? DatabaseConnection)?.requestor
        if (isHelperRequestor(requestor)) return
        val dataSource = connection.connectionPoint.dataSource
        val identity = profile.identity(dataSource)
        val console = requestor is DataProducer
        if (console) {
            consoleConnections[requestor] = ConsoleConnection(connection, identity)
            // A new connection starts outside any transaction.
            synchronized(sessionRequestors) { sessionRequestors.filterValues { it.get() === requestor }.keys.toList() }.forEach(explicitTransactions::remove)
        }
        val wanted = when (profile.trigger) {
            HarvestTrigger.ONCE_PER_SESSION -> !store.hasLive(dataSource.uniqueId, identity)
            HarvestTrigger.EVERY_CONSOLE_CONNECT -> console || !store.hasLive(dataSource.uniqueId, identity)
            HarvestTrigger.ON_REFRESH -> false
        }
        if (!wanted) return
        // A console of an every-connect dialect reads its own session's facts, so it never waits for another harvest.
        val claim = (dataSource.uniqueId to identity).takeUnless { console && profile.trigger == HarvestTrigger.EVERY_CONSOLE_CONNECT }
        if (claim != null && !inFlight.add(claim)) return
        try {
            val outcome = runHarvest(profile.harvester, profile.deadline) { RemoteCatalogRows(connection, it) }
            record(dataSource, identity, outcome, requestor.takeIf { console })
        } finally {
            claim?.let(inFlight::remove)
        }
    }

    /**
     * Harvests [dataSource] over a helper connection of its own, which the interceptor skips and which
     * runs no startup script ([HelperConnectionPoint]), and stores the shared inventory; console
     * sessions' facts stay. Cancellation propagates.
     */
    public suspend fun refresh(project: Project, dataSource: LocalDataSource): RefreshOutcome {
        val profile = profile
        if (!profile.enabled) return RefreshOutcome.Disabled
        val identity = profile.identity(dataSource)
        val ref = try {
            // The identity is taken before connecting, so a change while connecting cannot be mislabelled.
            DatabaseConnectionManager.getInstance().build(project, HelperConnectionPoint(dataSource, profile.connectionProperties(dataSource))).setRequestor(HelperRequestor()).create()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.info("Refreshing the live catalog of ${dataSource.name}: no connection: ${e.message}")
            return RefreshOutcome.ConnectFailed(e.message)
        } ?: return RefreshOutcome.ConnectFailed(null)
        return ref.use { connection ->
            val outcome = runHarvest(profile.harvester, profile.deadline) { RemoteCatalogRows(connection.get(), it) }
            when {
                !record(dataSource, identity, outcome, session = null) -> RefreshOutcome.Superseded
                outcome is HarvestOutcome.Complete -> RefreshOutcome.Refreshed(outcome.harvest.shared)
                else -> RefreshOutcome.HarvestFailed(outcome)
            }
        }
    }

    /**
     * Refreshes over [session]'s own connection, so the session's facts are read too (extensions it
     * loaded implicitly, for example DuckDB autoload): the harvest is queued on the session as a request
     * and runs on its engine's thread, with the same rules as after executed statements. A session in a
     * transaction is not harvested ([RefreshOutcome.SessionInTransaction]). A session that is not
     * connected falls back to [refresh] over a helper connection. Cancellation propagates; a harvest
     * cancelled before the session ran it does not run.
     */
    public suspend fun refresh(project: Project, session: DatabaseSession): RefreshOutcome {
        if (!profile.enabled) return RefreshOutcome.Disabled
        val dataSource = session.connectionPoint.dataSource
        if (!session.isConnected) return refresh(project, dataSource)
        // A request outside the session's transaction would make the engine end it; such a session is left alone.
        if (profile.clientTransactions && (session.currentTx != DataRequest.AUTO_COMMIT || session in explicitTransactions)) return RefreshOutcome.SessionInTransaction
        val result = CompletableDeferred<RefreshOutcome>()
        val request = object : DataRequest.RawRequest(session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                if (result.isCompleted) return
                result.complete(harvestSession(dataSource, session, context.producer, connection))
            }
        }
        request.promise.onError { error ->
            result.complete(RefreshOutcome.HarvestFailed(HarvestOutcome.Failed(error as? Exception ?: RuntimeException(error))))
        }
        request.promise.onSuccess { result.complete(RefreshOutcome.HarvestFailed(HarvestOutcome.Incomplete)) }
        try {
            session.messageBus.dataProducer.processRequest(request)
            return result.await()
        } finally {
            result.cancel()
        }
    }

    /** [refresh] in the background, over [session]'s connection when it is given, then [onRefreshed]. */
    public fun refreshInBackground(project: Project, dataSource: LocalDataSource, session: DatabaseSession? = null) {
        scope.launch {
            val outcome = if (session != null) refresh(project, session) else refresh(project, dataSource)
            onRefreshed(project, dataSource, outcome, RefreshReason.Manual)
        }
    }

    /** Harvests over a session's [connection] on its engine's thread, which [requestor] runs. */
    private fun harvestSession(dataSource: LocalDataSource, session: DatabaseSession, requestor: Any?, connection: DatabaseConnectionCore): RefreshOutcome {
        requestor?.let { learnSession(session, it) }
        val identity = requestor?.let { consoleConnections[it] }?.identity ?: profile.identity(dataSource)
        return harvestOver(dataSource, connection, identity, requestor, session)
    }

    private fun harvestOver(dataSource: LocalDataSource, connection: DatabaseConnectionCore, identity: ConnectionIdentity, requestor: Any?, session: DatabaseSession): RefreshOutcome {
        if (identity != profile.identity(dataSource)) {
            LOG.info("Not harvesting ${dataSource.name} over a connection to the server it named before")
            return RefreshOutcome.Superseded
        }
        if (profile.clientTransactions && (session in explicitTransactions || inTransaction(connection))) {
            LOG.info("Not harvesting ${dataSource.name} over a session in a transaction")
            return RefreshOutcome.SessionInTransaction
        }
        val outcome = runHarvestBlocking(profile.harvester, profile.deadline) { RemoteCatalogRows(connection, it) }
        return when {
            !record(dataSource, identity, outcome, requestor) -> RefreshOutcome.Superseded
            outcome is HarvestOutcome.Complete -> RefreshOutcome.Refreshed(outcome.harvest.shared, requestor?.let { outcome.harvest.session } ?: SessionFacts.NONE)
            else -> RefreshOutcome.HarvestFailed(outcome)
        }
    }

    /**
     * Called after statements that change the function inventory ran in [session], usually on its
     * engine's thread. When the change may belong to the session ([sessionFacts]) and the session's
     * connection is known, still reaches the same server and is in auto-commit mode, the harvest runs over
     * that connection on this thread, waiting at most the deadline, so the session's facts are read. A
     * session in a transaction is not harvested (a failing harvest query could abort it on some engines),
     * and a connection to a server the data source no longer names is not used. Otherwise, and never on
     * the EDT, a debounced [refresh] follows. [notify] shows the result (see [onRefreshed]).
     */
    public fun inventoryChanged(project: Project, session: DatabaseSession, requestor: Any?, sessionFacts: Boolean, notify: Boolean = false) {
        val profile = profile
        if (!profile.enabled) return
        val dataSource = session.connectionPoint.dataSource
        val reason = RefreshReason.Executed(notify)
        requestor?.let { learnSession(session, it) }
        val console = requestor?.let { consoleConnections[it] }
        val connection = console?.connection
        if (!sessionFacts || connection == null || ApplicationManager.getApplication().isDispatchThread) {
            debouncer.submit(dataSource.uniqueId) { onRefreshed(project, dataSource, refresh(project, dataSource), reason) }
            return
        }
        onRefreshed(project, dataSource, harvestOver(dataSource, connection, console.identity, requestor, session), reason)
    }

    /** Whether [connection] may be in a transaction: not in auto-commit mode, or unknown. */
    private fun inTransaction(connection: DatabaseConnectionCore): Boolean = try {
        !connection.autoCommit
    } catch (e: Exception) {
        LOG.info("The auto-commit mode is unknown: ${e.message}")
        true
    }

    /** Records that a statement in [session] began a transaction, or ended one. */
    internal fun transactionControl(session: DatabaseSession, begun: Boolean) {
        if (begun) explicitTransactions += session else explicitTransactions -= session
    }

    /** Records that [session]'s requests run through [requestor], the connection requestor its facts are kept under. */
    internal fun learnSession(session: DatabaseSession, requestor: Any) {
        sessionRequestors[session] = WeakReference(requestor)
    }

    /** Forgets [dataSourceId] everywhere, persisted harvests included. */
    public fun forget(dataSourceId: String) {
        store.forget(dataSourceId)
        completions.clear()
    }

    /**
     * Shows [outcome] in the profile's notification group, if it has one: for a manual refresh, or after
     * executed statements whose rule [StatementRule.notifies].
     */
    protected open fun onRefreshed(project: Project, dataSource: LocalDataSource, outcome: RefreshOutcome, reason: RefreshReason) {
        val group = profile.notificationGroupId ?: return
        val shown = when (reason) {
            RefreshReason.Manual -> true
            is RefreshReason.Executed -> reason.notify
        }
        if (!shown || outcome == RefreshOutcome.Disabled || project.isDisposed) return
        val label = profile.productLabel
        val (type, text) = when (outcome) {
            is RefreshOutcome.Refreshed -> NotificationType.INFORMATION to buildString {
                append("${dataSource.name}: ${outcome.inventory.functions.size} functions")
                outcome.inventory.serverVersion?.let { append(", $label $it") }
                if (outcome.inventory.extensions.isNotEmpty()) append(", extensions: ${outcome.inventory.extensions.joinToString(", ")}")
                val session = outcome.session
                if (session.functions.isNotEmpty() || session.extensions.isNotEmpty()) {
                    append("; this session: ${session.functions.size} functions")
                    if (session.extensions.isNotEmpty()) append(", extensions: ${session.extensions.joinToString(", ")}")
                }
            }
            is RefreshOutcome.ConnectFailed -> NotificationType.WARNING to "Could not connect to ${dataSource.name}" + (outcome.detail?.let { ": ${it.take(200)}" } ?: ".")
            is RefreshOutcome.HarvestFailed -> NotificationType.WARNING to "Reading the functions of ${dataSource.name} failed; the previous catalog stays."
            RefreshOutcome.Superseded -> NotificationType.WARNING to "${dataSource.name} changed while its functions were read; refresh again."
            RefreshOutcome.SessionInTransaction -> NotificationType.WARNING to "Not read: the console's session is in a transaction. Refresh after it ends."
            RefreshOutcome.Disabled -> return
        }
        notifyEscaped(project, group, "$label functions", text, type)
    }

    /**
     * Stores [outcome], harvested from [identity]; false when the data source names another server now, and
     * nothing was stored.
     */
    private fun record(dataSource: LocalDataSource, identity: ConnectionIdentity, outcome: HarvestOutcome, session: Any?): Boolean {
        if (profile.identity(dataSource) != identity) {
            LOG.info("The live catalog harvest of ${dataSource.name} is from the server it named before; discarded")
            return false
        }
        when (outcome) {
            is HarvestOutcome.Complete -> {
                store.store(dataSource.uniqueId, identity, outcome.harvest.shared)
                session?.let { store.storeSession(it, dataSource.uniqueId, identity, outcome.harvest.session) }
            }
            is HarvestOutcome.Failed -> LOG.info("The live catalog harvest of ${dataSource.name} failed; the previous catalog stays", outcome.error)
            HarvestOutcome.Incomplete -> LOG.info("The live catalog harvest of ${dataSource.name} was incomplete; the previous catalog stays")
            HarvestOutcome.TimedOut -> LOG.info("The live catalog harvest of ${dataSource.name} exceeded ${profile.deadline}; the previous catalog stays")
        }
        return true
    }

    /** Equal by the identity of what a merge reads, so a new harvest or snapshot merges again. */
    private class CompletionKey(val inventory: LiveInventory?, val session: SessionFacts, val snapshot: MetadataSnapshot, val baseline: List<FunctionPresentation>) {
        override fun equals(other: Any?): Boolean = other is CompletionKey && inventory === other.inventory &&
            session === other.session && snapshot === other.snapshot && baseline === other.baseline

        override fun hashCode(): Int = listOf(inventory, session, snapshot, baseline).fold(0) { hash, part -> 31 * hash + System.identityHashCode(part) }
    }

    private companion object {
        val LOG = logger<LiveCatalogService>()
    }
}

/** Opens this library's own helper connections (refreshes, text explain, verification), which harvests skip. */
internal class HelperRequestor : ConnectionRequestor by ConnectionRequestor.Anonymous() {
    // Every plugin's copy of this library defines its own class: the key, not the class, marks a helper.
    override fun toString(): String = HELPER_REQUESTOR_KEY
}

/** What marks a helper connection's requestor across plugins' copies of this library ([isHelperRequestor]). */
public const val HELPER_REQUESTOR_KEY: String = "dev.brikk.house.sql.intellij.helper-connection"

/**
 * A requestor for a helper connection: every plugin's copy of this library recognizes it
 * ([isHelperRequestor]), so harvests, connected-state checks and highlighting restarts skip it.
 */
public fun helperRequestor(): ConnectionRequestor = HelperRequestor()

/**
 * Whether [requestor] opened a helper connection, in this or another plugin's copy of this library:
 * by its key, or by the helper requestor's class name for copies before the key, never by class identity.
 */
public fun isHelperRequestor(requestor: Any?): Boolean =
    requestor != null && (requestor.javaClass.name == HELPER_REQUESTOR_CLASS || requestor.toString() == HELPER_REQUESTOR_KEY)

private val HELPER_REQUESTOR_CLASS: String = HelperRequestor::class.java.name

/**
 * `<database.connectionInterceptor>`: harvests new connections of the profile's dbms through the
 * service ([LiveCatalogService.connected]) as its trigger says, before the connection is handed over.
 * Register a no-arg subclass naming the plugin's service class.
 */
// No compatibility bridges for the interface's unused defaults (one is scheduled for removal).
@JvmDefaultWithoutCompatibility
public abstract class HarvestConnectionInterceptor(private val service: Class<out LiveCatalogService>) : DatabaseConnectionInterceptor {
    private val catalog: LiveCatalogService? get() = ApplicationManager.getApplication().getService(service)

    override suspend fun interceptConnection(proto: DatabaseConnectionInterceptor.ProtoConnection, silent: Boolean): Boolean = guarded(false) {
        val catalog = catalog ?: return@guarded false
        // Consoles are intercepted under every trigger: their connections are where session facts are read.
        catalog.profile.enabled && catalog.accepts(proto.connectionPoint.dbms)
    }

    override suspend fun handleConnected(connection: DatabaseConnectionCore, proto: DatabaseConnectionInterceptor.ProtoConnection) {
        guarded(Unit) { catalog?.connected(connection) }
    }

    /** The connection proceeds whatever a plugin's harvester throws; only control flow and cancellation pass. */
    private inline fun <T> guarded(fallback: T, action: () -> T): T = try {
        action()
    } catch (e: Throwable) {
        if (e is ControlFlowException || e is CancellationException || e is VirtualMachineError) throw e
        logger<HarvestConnectionInterceptor>().warn("The live catalog harvest failed", e)
        fallback
    }
}

/**
 * Runs an action once per key after a quiet [delay]: further submissions for a key while it waits join
 * it. A submission while the action runs opens a new wait, so a change is never lost.
 */
internal class RefreshDebouncer(private val scope: CoroutineScope, private val delay: Duration) {
    private val pending = ConcurrentHashMap<Any, Any>()

    /** True when [key] had no waiting action and [action] was scheduled. */
    fun submit(key: Any, action: suspend () -> Unit): Boolean {
        val token = Any()
        if (pending.putIfAbsent(key, token) != null) return false
        val job = scope.launch {
            delay(delay)
            pending.remove(key, token)
            action()
        }
        job.invokeOnCompletion { pending.remove(key, token) }
        return true
    }
}

/**
 * Refreshes the live catalog of the editor's data source, or of the project's only data source of the
 * dbms. In the editor popup it is absent unless the SQL at the caret (the file, or SQL injected there) is
 * served by a data source of the dbms: SQL of the engine's dialect ([SqlDialectRegistry]) as the resolver
 * finds it, generic SQL only through an explicit console or resolution scope, never another engine's SQL,
 * another file or a missing editor. Register a no-arg subclass naming the plugin's service class.
 */
public abstract class RefreshLiveCatalogAction(private val service: Class<out LiveCatalogService>) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val catalog = ApplicationManager.getApplication().getService(service)
        val target = if (project != null && catalog?.profile?.enabled == true) target(project, catalog, e) else null
        e.presentation.isVisible = project != null && (target != null || e.place != ActionPlaces.EDITOR_POPUP)
        e.presentation.isEnabled = target != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val catalog = ApplicationManager.getApplication().getService(service) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val dataSource = target(project, catalog, e) ?: return
        // The editor's console session, when connected, also reads what that session loaded.
        val session = file?.let(catalog::consoleSessionOf)?.takeIf { it.connectionPoint.dataSource == dataSource && it.isConnected }
        catalog.refreshInBackground(project, dataSource, session)
    }

    private fun target(project: Project, catalog: LiveCatalogService, e: AnActionEvent): LocalDataSource? {
        if (e.place == ActionPlaces.EDITOR_POPUP) return popupTarget(catalog, e)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        if (file != null) return catalog.dataSourceOf(file)
        return recoverEditorOperation {
            LocalDataSourceManager.getInstance(project).dataSources.filter { catalog.accepts(it.dbms) }.singleOrNull()
        }.getOrNull()
    }

    /** The data source serving the SQL at the editor popup's caret, by its dialect; null where none plainly does. */
    private fun popupTarget(catalog: LiveCatalogService, e: AnActionEvent): LocalDataSource? {
        val sql = sqlAtCaret(e) ?: return null
        val dialect = SqlDialectRegistry.forLanguage(sql.language)
        val resolution = catalog.resolutionOf(sql)
        return when {
            // The engine's own SQL: the project's only data source of the dbms serves it too.
            dialect != null && dialect == catalog.dialect -> resolution.dataSource
            // Another engine's SQL is never this one's.
            dialect != null -> null
            // Generic SQL names no engine: only an explicit console or scope binds it.
            resolution is DataSourceResolution.Console || resolution is DataSourceResolution.FileMapping -> resolution.dataSource
            else -> null
        }
    }

    /**
     * The SQL file at the editor's caret: SQL injected there, else the editor's file when it is SQL. Only an
     * editor showing the file itself counts: a host editor with its host file, or an injected editor with its
     * injected file; a missing editor, another project's, or one of another document (a host editor with an
     * injected file, say) gives nothing rather than a caret read against the wrong text.
     */
    private fun sqlAtCaret(e: AnActionEvent): PsiFile? = recoverEditorOperation {
        val project = e.project ?: return@recoverEditorOperation null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return@recoverEditorOperation null
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return@recoverEditorOperation null
        if (!editorShowsFile(project, editor, file)) return@recoverEditorOperation null
        val injections = InjectedLanguageManager.getInstance(project)
        val injected = if (injections.isInjectedFragment(file)) null else injections.findInjectedElementAt(file, editor.caretModel.offset)?.containingFile
        (injected ?: file).takeIf { it.language is SqlLanguageDialect }
    }.getOrNull()
}

/**
 * Forgets a deleted data source's live catalog, persisted files included. Register a no-arg subclass as
 * a listener of `com.intellij.database.dataSource.DataSourceStorage$Listener` under both
 * `projectListeners` and `applicationListeners` (project and global data sources).
 */
public abstract class LiveCatalogCleanup(private val service: Class<out LiveCatalogService>) : DataSourceStorage.Listener {
    override fun dataSourceRemoved(dataSource: LocalDataSource) {
        ApplicationManager.getApplication().getService(service)?.forget(dataSource.uniqueId)
    }
}
