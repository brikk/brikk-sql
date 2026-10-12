package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.introspection.IntrospectionTask
import com.intellij.database.introspection.prepareOneElementRefreshTask
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.LoaderContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import dev.brikk.house.sql.intellij.recoverEditorOperation
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus

/**
 * Which namespaces were introspected automatically. A request is claimed once while in flight and
 * after it succeeds; after a failure it may be claimed again once [retryDelay] has passed.
 */
@ApiStatus.Experimental
public class AutoIntrospectEngine(
    private val retryDelay: Duration = 1.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** A namespace of a data source, by its absolute path. */
    public data class Key(val dataSourceId: String, val path: List<String>)

    public sealed interface State {
        public data object InFlight : State
        public data object Done : State
        public data class Failed(val retryAt: TimeMark) : State
    }

    private val states = ConcurrentHashMap<Key, State>()

    public fun state(key: Key): State? = states[key]

    /** Marks [key] in flight unless it is in flight, done, or failed too recently; true when claimed. */
    public fun claim(key: Key): Boolean {
        var claimed = false
        states.compute(key) { _, state ->
            val free = state == null || state is State.Failed && state.retryAt.hasPassedNow()
            claimed = free
            if (free) State.InFlight else state
        }
        return claimed
    }

    /** Like [claim], but a failure does not hold back a request the user asked for. */
    internal fun claimNow(key: Key): Boolean {
        var claimed = false
        states.compute(key) { _, state ->
            claimed = state == null || state is State.Failed
            if (claimed) State.InFlight else state
        }
        return claimed
    }

    /** Forgets an in-flight claim that never ran. */
    internal fun release(key: Key) {
        states.remove(key, State.InFlight)
    }

    public fun finish(key: Key, succeeded: Boolean) {
        states[key] = if (succeeded) State.Done else State.Failed(timeSource.markNow() + retryDelay)
    }
}

/**
 * Introspects, one level, what a path typed in an editor names when the model lists it but has not
 * loaded its children. The data source's introspection scope gains exactly that namespace, so what
 * loads stays loaded, and only that model element is refreshed, never the whole scope. While a
 * request runs, its file shows a banner ([IntrospectBannerProvider]); when the refresh ends the
 * banner goes and, after success, the file is highlighted again.
 *
 * Register a project service subclass, one per plugin, with the platform's
 * `(Project, CoroutineScope)` constructor. The refresh is the platform's one-element task
 * (`prepareOneElementRefreshTask`) through `DataSourceSyncManager.tryPerformSync`, the sync entry
 * point that remains in 263.
 */
@ApiStatus.Experimental
public abstract class AutoIntrospectService(protected val project: Project, private val scope: CoroutineScope) {
    public abstract val profile: CatalogProfile

    /** The request states; override to set the retry delay. */
    protected open fun createEngine(): AutoIntrospectEngine = AutoIntrospectEngine()

    public val engine: AutoIntrospectEngine by lazy { createEngine() }

    private val dataSources by lazy { profile.dataSources }
    private val pathResolver by lazy { profile.pathResolver }

    /** A refresh; [file] shows its banner, and only a [claimed] one widens the scope and settles the engine's state. */
    private class Request(
        val key: AutoIntrospectEngine.Key,
        val dataSource: LocalDataSource,
        val task: IntrospectionTask,
        val file: VirtualFile?,
        val claimed: Boolean,
    )

    /** Namespaces a refresh of this service loaded, which stay loaded when a statement empties them. */
    private val loaded = ConcurrentHashMap.newKeySet<AutoIntrospectEngine.Key>()

    /** Running and queued requests, oldest first; a request's banner shows while it is here. */
    private val banners = LinkedHashSet<Request>()
    private val workers = HashMap<String, Worker>()

    /**
     * Introspects the namespace [path] names in [file] (as typed, absolute or relative to the console's
     * namespace) when it is listed but childless. True when a refresh was queued.
     */
    public fun requestPath(file: PsiFile, path: List<String>): Boolean {
        if (!profile.autoIntrospect) return false
        return recoverEditorOperation {
            val dataSource = dataSources.resolve(file).dataSource ?: return@recoverEditorOperation false
            val model = DbPsiFacade.getInstance(project).findDataSource(dataSource.uniqueId)?.model ?: return@recoverEditorOperation false
            val current = if (profile.usesCurrentNamespace) dataSources.consoleNamespace(file) else emptyList()
            val target = pathResolver.resolve(model.modelRoots.toList(), path, current) ?: return@recoverEditorOperation false
            pathResolver.isChildless(target.node) && request(file, dataSource, target)
        }.getOrElse { e ->
            LOG.warn("Auto-introspection of $path failed", e)
            false
        }
    }

    /**
     * Introspects [target] of [dataSource] for [file], unless it is claimed. True when a refresh was
     * queued. Each data source refreshes one target at a time, in request order.
     */
    public fun request(file: PsiFile, dataSource: LocalDataSource, target: NamespaceTarget): Boolean =
        profile.autoIntrospect && queue(file, dataSource, target, engine::claim)

    /**
     * Introspects [target] of [dataSource] for [file] because the user asked to
     * ([IntrospectNamespaceIntention]): also when automatic introspection is off, and right after a
     * failed attempt. Not while it is in flight, or once it loaded. True when a refresh was queued.
     */
    public fun introspect(file: PsiFile, dataSource: LocalDataSource, target: NamespaceTarget): Boolean =
        queue(file, dataSource, target, engine::claimNow)

    private fun queue(file: PsiFile, dataSource: LocalDataSource, target: NamespaceTarget, claim: (AutoIntrospectEngine.Key) -> Boolean): Boolean {
        val element = target.node as? BasicElement ?: return false
        val task = prepareOneElementRefreshTask(dataSource.uniqueId, element) ?: return false
        val key = AutoIntrospectEngine.Key(dataSource.uniqueId, target.path)
        if (!claim(key)) return false
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val request = Request(key, dataSource, task, virtualFile, claimed = true)
        synchronized(banners) { banners += request }
        if (!enqueue(request)) {
            synchronized(banners) { banners -= request }
            engine.release(key)
            return false
        }
        EditorNotifications.getInstance(project).updateNotifications(virtualFile)
        return true
    }

    /**
     * Reloads the namespace at [path] of [dataSource] (empty: its top-level namespaces) after executed
     * statements changed its children, when it was loaded: it has children, the introspection scope
     * selects it, or a refresh of this service loaded it (an emptied namespace stays loaded). What was
     * never loaded is not stale. The scope is left as it is, and the refresh may run any number of times.
     * True when it was queued.
     */
    public fun refreshChanged(dataSource: LocalDataSource, path: List<String>): Boolean = recoverEditorOperation {
        val model = DbPsiFacade.getInstance(project).findDataSource(dataSource.uniqueId)?.model ?: return@recoverEditorOperation false
        val key = AutoIntrospectEngine.Key(dataSource.uniqueId, path)
        val element = if (path.isEmpty()) (model as? BasicModel)?.root
        else pathResolver.resolve(model.modelRoots.toList(), path)?.takeIf { wasLoaded(dataSource, it) }?.node as? BasicElement
        val task = element?.let { prepareOneElementRefreshTask(dataSource.uniqueId, it) } ?: return@recoverEditorOperation false
        enqueue(Request(key, dataSource, task, file = null, claimed = false))
    }.getOrElse { e ->
        LOG.warn("Refreshing $path of ${dataSource.name} failed", e)
        false
    }

    private fun wasLoaded(dataSource: LocalDataSource, target: NamespaceTarget): Boolean {
        val key = AutoIntrospectEngine.Key(dataSource.uniqueId, target.path)
        return !pathResolver.isChildless(target.node) || key in loaded || engine.state(key) == AutoIntrospectEngine.State.Done ||
            CatalogScopes.selects(dataSource.introspectionScope, profile.levels, target.path)
    }

    private fun enqueue(request: Request): Boolean =
        synchronized(workers) { workers.getOrPut(request.dataSource.uniqueId, ::Worker) }.offer(request)

    /** The namespace of [file]'s newest request, if any. */
    public fun pending(file: VirtualFile): String? =
        synchronized(banners) { banners.lastOrNull { it.file == file }?.key?.path?.joinToString(".") }

    /** Hides [file]'s banner; the refreshes go on. */
    public fun dismiss(file: VirtualFile) {
        synchronized(banners) { banners.removeAll { it.file == file } }
        EditorNotifications.getInstance(project).updateNotifications(file)
    }

    /**
     * Refreshes one data source's requests one after another. The platform's own queue may drop a
     * waiting refresh when another arrives, and the scope's read-modify-write must not interleave.
     */
    private inner class Worker {
        private val queue = ArrayDeque<Request>()
        private var job: Job? = null

        fun offer(request: Request): Boolean = synchronized(this) {
            if (queue.size >= MAX_QUEUED) return false
            queue.addLast(request)
            if (job == null) {
                val started = scope.launch { drain() }
                job = started
                started.invokeOnCompletion { abandon(started) }
            }
            true
        }

        private suspend fun drain() {
            while (true) {
                val next = synchronized(this) { queue.removeFirstOrNull() ?: null.also { job = null } } ?: return
                refresh(next)
            }
        }

        /** Releases what a cancelled or failed worker left queued. */
        private fun abandon(ended: Job) {
            val left = synchronized(this) {
                if (job !== ended) return
                job = null
                queue.toList().also { queue.clear() }
            }
            left.forEach { finish(it, loaded = false) }
        }
    }

    private suspend fun refresh(request: Request) {
        var loaded = false
        try {
            val dataSource = request.dataSource
            if (request.claimed) {
                dataSource.introspectionScope = CatalogScopes.widen(dataSource.introspectionScope, CatalogScopes.select(profile.levels, request.key.path))
                storageOf(dataSource)?.updateDataSource(dataSource)
            }
            loaded = sync(request) && (!request.claimed || readAction { isLoaded(request) })
        } finally {
            finish(request, loaded)
        }
        if (loaded && request.key.path.isNotEmpty()) this.loaded += request.key
        if (loaded) request.file?.let { highlightAgain(it) }
    }

    /** Whether the sync ran without errors; a data source that is loading does not sync and gives no result. */
    private suspend fun sync(request: Request): Boolean = try {
        val context = LoaderContext.selectTask(project, request.dataSource, request.task)
        // Not stopping a running refresh: refreshes started elsewhere are not ours to cancel.
        val result = DataSourceSyncManager.getInstance().tryPerformSync(context, stopRunning = false, merge = false)
        result != null && context.errorHandler?.hasErrors() != true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LOG.warn("Introspecting ${request.key.path} of ${request.dataSource.name} failed", e)
        false
    }

    /** Whether the target now has children: a refresh that loaded nothing may run again. */
    private fun isLoaded(request: Request): Boolean {
        val model = DbPsiFacade.getInstance(project).findDataSource(request.key.dataSourceId)?.model ?: return false
        val node = pathResolver.resolve(model.modelRoots.toList(), request.key.path)?.node ?: return false
        return !pathResolver.isChildless(node)
    }

    private fun finish(request: Request, loaded: Boolean) {
        if (request.claimed) engine.finish(request.key, loaded)
        val file = request.file ?: return
        synchronized(banners) { banners -= request }
        if (!project.isDisposed) EditorNotifications.getInstance(project).updateNotifications(file)
    }

    private fun storageOf(dataSource: LocalDataSource): DataSourceStorage? =
        listOfNotNull(DataSourceStorage.getProjectStorage(project), DataSourceStorage.getStorage()).firstOrNull { dataSource in it.ownDataSources }

    /** The daemon's restart, like the PSI lookup, needs read access. */
    private suspend fun highlightAgain(file: VirtualFile) = readAction {
        if (!project.isDisposed && file.isValid) {
            PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it, "introspected") }
        }
    }

    private companion object {
        val LOG = logger<AutoIntrospectService>()

        /** Requests a data source may have waiting; more are refused, not queued. */
        const val MAX_QUEUED = 32
    }
}

/**
 * The banner of a file whose auto-introspection runs: "<product>: introspecting '<namespace>'…".
 * Register a no-arg subclass naming the plugin's service class as `editorNotificationProvider`.
 */
@ApiStatus.Experimental
public abstract class IntrospectBannerProvider(private val service: Class<out AutoIntrospectService>) : EditorNotificationProvider {
    final override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val requests = project.getService(service) ?: return null
        val namespace = requests.pending(file) ?: return null
        val label = requests.profile.productLabel
        return Function {
            EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                text = "$label: introspecting '$namespace'\u2026 invoke completion again when it finishes " +
                    "(if nothing appears, introspect it in the Database view)."
                createActionLabel("Dismiss") { requests.dismiss(file) }
            }
        }
    }
}
