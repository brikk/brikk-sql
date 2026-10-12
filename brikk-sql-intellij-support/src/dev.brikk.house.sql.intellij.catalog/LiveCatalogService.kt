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
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.completion.completableFunctions
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus

/** Which connections harvest. A refresh always does. */
@ApiStatus.Experimental
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
@ApiStatus.Experimental
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

    /** The functions offered without a harvest. */
    public fun baseline(snapshot: MetadataSnapshot): List<FunctionPresentation> = snapshot.completableFunctions()
}

/** Why a refresh ran. */
@ApiStatus.Experimental
public enum class RefreshReason {
    /** The user asked for it. */
    MANUAL,

    /** Executed statements changed the function inventory. */
    EXECUTED,
}

/** How a refresh ended. */
@ApiStatus.Experimental
public sealed interface RefreshOutcome {
    public data class Refreshed(val inventory: LiveInventory) : RefreshOutcome

    /** The profile is not enabled. */
    public data object Disabled : RefreshOutcome

    public data class ConnectFailed(val detail: String?) : RefreshOutcome

    /** Connected, but the harvest failed, timed out or read an incomplete inventory; the previous catalog stays. */
    public data class HarvestFailed(val outcome: HarvestOutcome) : RefreshOutcome
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
@ApiStatus.Experimental
public abstract class LiveCatalogService(protected val scope: CoroutineScope) {
    public abstract val profile: LiveCatalogProfile

    /** Where harvests persist; tests override it. */
    protected open fun persistenceDirectory(): Path? =
        profile.persistenceId?.let { Path.of(PathManager.getSystemPath(), "brikk-live-catalog", it) }

    public val store: LiveCatalogStore by lazy {
        val profile = profile
        LiveCatalogStore(persistenceDirectory(), profile.legacyFiles).also { store ->
            scope.launch(Dispatchers.IO) { store.sweep(profile.maxAge) }
        }
    }

    private val debouncer by lazy { RefreshDebouncer(scope, profile.refreshDelay) }
    private val inFlight = ConcurrentHashMap.newKeySet<Pair<String, ConnectionIdentity>>()

    /**
     * The latest connection of each console engine (its connection requestor). Values are weak too: a
     * connection references its requestor and an engine its session, which would keep the keys alive.
     */
    private val consoleConnections: MutableMap<Any, WeakReference<DatabaseConnectionCore>> = Collections.synchronizedMap(WeakHashMap())

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

    /** What completion offers in [file]: the selection for its data source and console session, merged with the bundled functions. */
    public fun completion(file: PsiFile): LiveCompletion {
        val profile = profile
        val dataSource = profile.dataSources.resolve(file).dataSource
        val selection = dataSource?.let { selection(it, profile.dataSources.consoleSession(file)) } ?: LiveCatalogSelection.BUNDLED
        val version = selection.inventory?.serverVersion ?: dataSource?.databaseVersion?.version
        val snapshot = profile.metadata.forServerVersion(version).snapshot
        val baseline = profile.baseline(snapshot)
        val key = CompletionKey(selection.inventory, selection.session, snapshot, baseline)
        return completions[key] ?: profile.mergePolicy.merge(selection, snapshot, baseline).also { completions[key] = it }
    }

    /** Harvests over [connection] if the profile's trigger asks for it; called by [HarvestConnectionInterceptor]. */
    public suspend fun connected(connection: DatabaseConnectionCore) {
        val profile = profile
        if (!profile.enabled || connection.dbms.name != profile.dbms.name) return
        val requestor = (connection as? DatabaseConnection)?.requestor
        if (requestor is HarvestRequestor) return
        val dataSource = connection.connectionPoint.dataSource
        val identity = profile.identity(dataSource)
        val console = requestor is DataProducer
        if (console) consoleConnections[requestor] = WeakReference(connection)
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
     * Harvests [dataSource] over a helper connection of its own, which the interceptor skips, and stores
     * the shared inventory; console sessions' facts stay. Cancellation propagates.
     */
    public suspend fun refresh(project: Project, dataSource: LocalDataSource): RefreshOutcome {
        val profile = profile
        if (!profile.enabled) return RefreshOutcome.Disabled
        val identity = profile.identity(dataSource)
        val ref = try {
            DatabaseConnectionManager.getInstance().build(project, dataSource).setRequestor(HarvestRequestor()).create()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.info("Refreshing the live catalog of ${dataSource.name}: no connection: ${e.message}")
            return RefreshOutcome.ConnectFailed(e.message)
        } ?: return RefreshOutcome.ConnectFailed(null)
        return ref.use { connection ->
            val outcome = runHarvest(profile.harvester, profile.deadline) { RemoteCatalogRows(connection.get(), it) }
            record(dataSource, identity, outcome, session = null)
            if (outcome is HarvestOutcome.Complete) RefreshOutcome.Refreshed(outcome.harvest.shared) else RefreshOutcome.HarvestFailed(outcome)
        }
    }

    /** [refresh] in the background, then [onRefreshed]. */
    public fun refreshInBackground(project: Project, dataSource: LocalDataSource) {
        scope.launch { onRefreshed(project, dataSource, refresh(project, dataSource), RefreshReason.MANUAL) }
    }

    /**
     * Called after statements that change the function inventory ran in [session], on its engine's
     * thread: harvests over the session's own connection, so its facts are read too, waiting at most the
     * deadline. Without a known connection, a debounced [refresh] follows instead.
     */
    public fun inventoryChanged(project: Project, session: DatabaseSession, requestor: Any?) {
        val profile = profile
        if (!profile.enabled) return
        val dataSource = session.connectionPoint.dataSource
        requestor?.let { learnSession(session, it) }
        val connection = requestor?.let { consoleConnections[it]?.get() }
        if (connection == null) {
            debouncer.submit(dataSource.uniqueId) { onRefreshed(project, dataSource, refresh(project, dataSource), RefreshReason.EXECUTED) }
            return
        }
        val identity = profile.identity(dataSource)
        val outcome = runHarvestBlocking(profile.harvester, profile.deadline) { RemoteCatalogRows(connection, it) }
        record(dataSource, identity, outcome, requestor)
        val refreshed = if (outcome is HarvestOutcome.Complete) RefreshOutcome.Refreshed(outcome.harvest.shared) else RefreshOutcome.HarvestFailed(outcome)
        onRefreshed(project, dataSource, refreshed, RefreshReason.EXECUTED)
    }

    /** Records that [session]'s requests run through [requestor], the connection requestor its facts are kept under. */
    public fun learnSession(session: DatabaseSession, requestor: Any) {
        sessionRequestors[session] = WeakReference(requestor)
    }

    /** Forgets [dataSourceId] everywhere, persisted harvests included. */
    public fun forget(dataSourceId: String) {
        store.forget(dataSourceId)
        completions.clear()
    }

    /** Shows [outcome] in the profile's notification group, if it has one, whatever the [reason]. */
    protected open fun onRefreshed(project: Project, dataSource: LocalDataSource, outcome: RefreshOutcome, reason: RefreshReason) {
        val group = profile.notificationGroupId ?: return
        if (outcome == RefreshOutcome.Disabled || project.isDisposed) return
        val label = profile.productLabel
        val (type, text) = when (outcome) {
            is RefreshOutcome.Refreshed -> NotificationType.INFORMATION to buildString {
                append("${dataSource.name}: ${outcome.inventory.functions.size} functions")
                outcome.inventory.serverVersion?.let { append(", $label $it") }
                if (outcome.inventory.extensions.isNotEmpty()) append(", extensions: ${outcome.inventory.extensions.joinToString(", ")}")
            }
            is RefreshOutcome.ConnectFailed -> NotificationType.WARNING to "Could not connect to ${dataSource.name}" + (outcome.detail?.let { ": ${it.take(200)}" } ?: ".")
            is RefreshOutcome.HarvestFailed -> NotificationType.WARNING to "Reading the functions of ${dataSource.name} failed; the previous catalog stays."
            RefreshOutcome.Disabled -> return
        }
        notifyEscaped(project, group, "$label functions", text, type)
    }

    private fun record(dataSource: LocalDataSource, identity: ConnectionIdentity, outcome: HarvestOutcome, session: Any?) {
        when (outcome) {
            is HarvestOutcome.Complete -> {
                store.store(dataSource.uniqueId, identity, outcome.harvest.shared)
                session?.let { store.storeSession(it, dataSource.uniqueId, identity, outcome.harvest.session) }
            }
            is HarvestOutcome.Failed -> LOG.info("The live catalog harvest of ${dataSource.name} failed; the previous catalog stays", outcome.error)
            HarvestOutcome.Incomplete -> LOG.info("The live catalog harvest of ${dataSource.name} was incomplete; the previous catalog stays")
            HarvestOutcome.TimedOut -> LOG.info("The live catalog harvest of ${dataSource.name} exceeded ${profile.deadline}; the previous catalog stays")
        }
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

/** The requestor of the helper connections refreshes open, which the interceptor recognizes and skips. */
internal class HarvestRequestor : ConnectionRequestor by ConnectionRequestor.Anonymous()

/**
 * `<database.connectionInterceptor>`: harvests new connections of the profile's dbms through the
 * service ([LiveCatalogService.connected]) as its trigger says, before the connection is handed over.
 * Register a no-arg subclass naming the plugin's service class.
 */
@ApiStatus.Experimental
public abstract class HarvestConnectionInterceptor(private val service: Class<out LiveCatalogService>) : DatabaseConnectionInterceptor {
    private val catalog: LiveCatalogService? get() = ApplicationManager.getApplication().getService(service)

    override suspend fun interceptConnection(proto: DatabaseConnectionInterceptor.ProtoConnection, silent: Boolean): Boolean {
        val profile = catalog?.profile ?: return false
        return profile.enabled && profile.trigger != HarvestTrigger.ON_REFRESH && proto.connectionPoint.dbms.name == profile.dbms.name
    }

    override suspend fun handleConnected(connection: DatabaseConnectionCore, proto: DatabaseConnectionInterceptor.ProtoConnection) {
        try {
            catalog?.connected(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The connection proceeds whatever happens to the harvest.
            logger<HarvestConnectionInterceptor>().warn("The live catalog harvest failed", e)
        }
    }
}

/**
 * Runs an action once per key after a quiet [delay]: further submissions for a key while it waits join
 * it. A submission while the action runs opens a new wait, so a change is never lost.
 */
@ApiStatus.Experimental
public class RefreshDebouncer(private val scope: CoroutineScope, private val delay: Duration = 2.seconds) {
    private val pending = ConcurrentHashMap<Any, Any>()

    /** True when [key] had no waiting action and [action] was scheduled. */
    public fun submit(key: Any, action: suspend () -> Unit): Boolean {
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
 * dbms. In the editor popup it shows only where a data source of the dbms serves the file. Register a
 * no-arg subclass naming the plugin's service class.
 */
@ApiStatus.Experimental
public abstract class RefreshLiveCatalogAction(private val service: Class<out LiveCatalogService>) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val catalog = ApplicationManager.getApplication().getService(service)
        val target = if (project != null && catalog?.profile?.enabled == true) target(project, catalog, e.getData(CommonDataKeys.PSI_FILE)) else null
        e.presentation.isVisible = project != null && (target != null || e.place != ActionPlaces.EDITOR_POPUP)
        e.presentation.isEnabled = target != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val catalog = ApplicationManager.getApplication().getService(service) ?: return
        target(project, catalog, e.getData(CommonDataKeys.PSI_FILE))?.let { catalog.refreshInBackground(project, it) }
    }

    private fun target(project: Project, catalog: LiveCatalogService, file: PsiFile?): LocalDataSource? {
        val profile = catalog.profile
        if (file != null) return profile.dataSources.resolve(file).dataSource
        return recoverEditorOperation {
            LocalDataSourceManager.getInstance(project).dataSources.filter { it.dbms.name == profile.dbms.name }.singleOrNull()
        }.getOrNull()
    }
}

/**
 * Forgets a deleted data source's live catalog, persisted files included. Register a no-arg subclass as
 * a listener of `com.intellij.database.dataSource.DataSourceStorage$Listener` under both
 * `projectListeners` and `applicationListeners` (project and global data sources).
 */
@ApiStatus.Experimental
public abstract class LiveCatalogCleanup(private val service: Class<out LiveCatalogService>) : DataSourceStorage.Listener {
    override fun dataSourceRemoved(dataSource: LocalDataSource) {
        ApplicationManager.getApplication().getService(service)?.forget(dataSource.uniqueId)
    }
}
