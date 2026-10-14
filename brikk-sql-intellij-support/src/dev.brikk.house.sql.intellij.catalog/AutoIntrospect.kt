package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.introspection.IntrospectionTask
import com.intellij.database.introspection.prepareOneElementRefreshTask
import com.intellij.database.model.DasModel
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicNamespace
import com.intellij.database.model.basic.BasicModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.LoaderContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Forgets every finished state of [dataSourceId], as after its model was cleared; requests in flight stay. */
    internal fun forget(dataSourceId: String) {
        states.entries.removeIf { (key, state) -> key.dataSourceId == dataSourceId && state != State.InFlight }
    }
}

/**
 * Introspects, one level, what a path typed in an editor names when the model lists it but has not
 * loaded its children, or loaded only some of them (single tables loaded on demand). The data source's
 * introspection scope gains exactly that namespace, so what loads stays loaded, and only that model
 * element is refreshed, never the whole scope. A path the model does not hold yet (the model was
 * cleared, or a namespace was never listed) is discovered step by step: the top-level namespaces are
 * listed once, cheaply, then each level down to the path's is loaded, and a name still missing after
 * that stays unknown. While a request runs, its file shows a banner ([IntrospectBannerProvider]); when
 * it ends the banner goes and, after success, the file is highlighted again and completion, if the
 * caret still follows the path, shown again. Nothing runs on the EDT, nor while the file's console is
 * in a transaction ([CatalogProfile.clientTransactions]); each step logs one INFO line.
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

    /** Work of a data source's worker, shown in [file]'s banner as [label] while it waits or runs. */
    private sealed interface Work {
        val dataSource: LocalDataSource
        val file: VirtualFile?
        val label: String
    }

    /** A refresh; [file] shows its banner, and only a [claimed] one widens the scope and settles the engine's state. */
    private class Request(
        val key: AutoIntrospectEngine.Key,
        override val dataSource: LocalDataSource,
        val task: IntrospectionTask,
        override val file: VirtualFile?,
        val claimed: Boolean,
        /** The path as typed that this load serves. */
        val requested: List<String> = key.path,
    ) : Work {
        override val label: String get() = key.path.joinToString(".")
    }

    /** Finding [path] typed in [file], which the model does not hold yet, a level at a time. */
    private class Discovery(override val dataSource: LocalDataSource, val path: List<String>, override val file: VirtualFile) : Work {
        override val label: String get() = path.joinToString(".")
    }

    /** What a data source's model held when last seen, and whether its top-level namespaces were listed since it was cleared. */
    private class Inventory(@Volatile var model: DasModel, @Volatile var hadRoots: Boolean) {
        @Volatile var rootsListed = false
    }

    private val inventories = ConcurrentHashMap<String, Inventory>()

    /** Namespaces a refresh of this service loaded, which stay loaded when a statement empties them. */
    private val loaded = ConcurrentHashMap.newKeySet<AutoIntrospectEngine.Key>()

    /** Namespaces whose last refresh by this service ran without errors, empty ones included. */
    private val synced = ConcurrentHashMap.newKeySet<AutoIntrospectEngine.Key>()

    /** Running and queued work, oldest first; its banner shows while it is here. */
    private val banners = LinkedHashSet<Work>()
    private val workers = HashMap<String, Worker>()

    /**
     * Introspects what [path] names in [file] (as typed, absolute or relative to the console's namespace):
     * a namespace or object that is listed but childless, or a namespace only partly loaded; or, when the
     * model does not hold the path yet, discovers it ([AutoIntrospectService]). True when work was queued.
     */
    public fun requestPath(file: PsiFile, path: List<String>): Boolean {
        if (!profile.autoIntrospect || path.isEmpty()) return false
        return recoverEditorOperation {
            val dataSource = dataSources.resolve(file).dataSource ?: return@recoverEditorOperation false
            // A cleared data source may have no model at all: then nothing is known, as in an empty one.
            val model = DbPsiFacade.getInstance(project).findDataSource(dataSource.uniqueId)?.model ?: dataSource.model
            val label = "${dataSource.name}: ${path.joinToString(".")}"
            if (inTransaction(file)) return@recoverEditorOperation skipped(label, "the console is in a transaction")
            inventoryOf(dataSource, model)
            val target = resolve(file, dataSource, path)
            if (target != null) {
                if (!needsLoad(dataSource, target)) return@recoverEditorOperation skipped(label, "already loaded")
                LOG.info("Auto-introspection requested $label")
                return@recoverEditorOperation request(file, dataSource, target)
            }
            if (path.size > profile.maxDeepenDepth) return@recoverEditorOperation skipped(label, "deeper than the namespaces")
            val step = nextStep(file, dataSource, path)
            if (step !is Step.Load && step != Step.ListRoots) return@recoverEditorOperation skipped(label, "not in the model, and nothing holding it to load")
            discover(file, dataSource, path)
        }.getOrElse { e ->
            LOG.warn("Auto-introspection of $path failed", e)
            false
        }
    }

    /**
     * Records that every child of the namespace at [path] of [dataSource] is in the model, as a dialect knows
     * after loading it its own way: requests for it then start nothing until its model is cleared.
     */
    public fun markLoaded(dataSource: LocalDataSource, path: List<String>) {
        val key = AutoIntrospectEngine.Key(dataSource.uniqueId, path)
        engine.finish(key, succeeded = true)
        loaded += key
        synced += key
        LOG.info("Auto-introspection: ${dataSource.name}: ${path.joinToString(".")} marked loaded by the dialect")
    }

    /** Logs why [label] starts nothing; false. */
    private fun skipped(label: String, why: String): Boolean {
        LOG.info("Auto-introspection skipped $label: $why")
        return false
    }

    private fun resolve(file: PsiFile, dataSource: LocalDataSource, path: List<String>): NamespaceTarget? {
        val model = modelOf(dataSource)
        return pathResolver.resolve(model.modelRoots.toList(), path, currentNamespace(file, model))
    }

    private fun modelOf(dataSource: LocalDataSource): DasModel =
        DbPsiFacade.getInstance(project).findDataSource(dataSource.uniqueId)?.model ?: dataSource.model

    /**
     * The namespace a relative path is under: the console's current one, else the one the model marks current
     * (the connection's default catalog and schema, as a listing reports them).
     */
    private fun currentNamespace(file: PsiFile, model: DasModel): List<String> {
        if (!profile.usesCurrentNamespace) return emptyList()
        dataSources.consoleNamespace(file).takeIf { it.isNotEmpty() }?.let { return it }
        val names = ArrayList<String>()
        var nodes = model.modelRoots.toList()
        while (names.size < profile.levels.size) {
            val current = nodes.firstOrNull { (it as? BasicNamespace)?.isCurrent == true } ?: break
            names += current.name
            nodes = profile.childrenOf(current)
        }
        return names
    }

    /** Whether [target] must load: it is childless, or a namespace whose children may be only some of them. */
    private fun needsLoad(dataSource: LocalDataSource, target: NamespaceTarget): Boolean =
        pathResolver.isChildless(target.node) || target.node.kind in profile.levels && !completelyLoaded(dataSource, target.path)

    /**
     * Whether every child of the namespace at [path] was loaded: a refresh of this service loaded it, or the
     * introspection scope selects it by name without restricting its children. Single objects loaded on
     * demand do not make a namespace complete.
     */
    private fun completelyLoaded(dataSource: LocalDataSource, path: List<String>): Boolean {
        val key = AutoIntrospectEngine.Key(dataSource.uniqueId, path)
        return engine.state(key) == AutoIntrospectEngine.State.Done || key in synced || key in loaded ||
            CatalogScopes.selectsWhole(dataSource.introspectionScope, profile.levels, path)
    }

    /** Whether [file]'s console is in a transaction, which introspection must not disturb. */
    private fun inTransaction(file: PsiFile): Boolean =
        profile.clientTransactions && dataSources.consoleSession(file)?.let { it.currentTx != DataRequest.AUTO_COMMIT } == true

    /**
     * Notes what [dataSource]'s [model] holds. A model emptied since it last held namespaces (Clear
     * Introspection Data) starts over: what this service loaded or listed is forgotten, so it loads again.
     */
    private fun inventoryOf(dataSource: LocalDataSource, model: DasModel) {
        val roots = model.modelRoots.any()
        inventories.compute(dataSource.uniqueId) { _, previous ->
            when {
                previous == null -> Inventory(model, roots)
                !roots && previous.hadRoots -> {
                    LOG.info("Auto-introspection: the model of ${dataSource.name} was cleared; loading again on demand")
                    engine.forget(dataSource.uniqueId)
                    loaded.removeIf { it.dataSourceId == dataSource.uniqueId }
                    synced.removeIf { it.dataSourceId == dataSource.uniqueId }
                    Inventory(model, hadRoots = false)
                }
                else -> previous.also {
                    it.model = model
                    if (roots) it.hadRoots = true
                }
            }
        }
    }

    /** The next step of finding [path]: list the top level, load a level, or stop. */
    private sealed interface Step {
        data object ListRoots : Step
        data class Load(val target: NamespaceTarget) : Step
        data object Found : Step
        data object Unknown : Step
    }

    private fun nextStep(file: PsiFile, dataSource: LocalDataSource, path: List<String>): Step {
        resolve(file, dataSource, path)?.let { target -> return if (needsLoad(dataSource, target)) Step.Load(target) else Step.Found }
        // The deepest prefix the model holds: if it must load, load it; if it is loaded, the name is not there.
        for (length in path.size - 1 downTo 1) {
            val ancestor = resolve(file, dataSource, path.take(length)) ?: continue
            return if (needsLoad(dataSource, ancestor)) Step.Load(ancestor) else Step.Unknown
        }
        // A first name may be under the current namespace, whose children may not be listed yet: only a level the
        // path fits below (`web` under the current catalog, not under its current schema; nothing for a.b.c).
        val current = currentNamespace(file, modelOf(dataSource))
        for (length in minOf(current.size, profile.levels.size - path.size) downTo 1) {
            val namespace = resolve(file, dataSource, current.take(length)) ?: continue
            if (needsLoad(dataSource, namespace)) return Step.Load(namespace)
            break
        }
        val inventory = inventories[dataSource.uniqueId] ?: return Step.ListRoots
        return if (inventory.rootsListed) Step.Unknown else Step.ListRoots
    }

    private fun discover(file: PsiFile, dataSource: LocalDataSource, path: List<String>): Boolean {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val work = Discovery(dataSource, path, virtualFile)
        synchronized(banners) {
            if (banners.any { it is Discovery && it.dataSource.uniqueId == dataSource.uniqueId && it.path == path }) return false
            banners += work
        }
        if (!enqueue(work)) {
            synchronized(banners) { banners -= work }
            return false
        }
        LOG.info("Auto-introspection requested ${dataSource.name}: ${path.joinToString(".")} (not in the model yet)")
        EditorNotifications.getInstance(project).updateNotifications(virtualFile)
        return true
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
        if (!claim(key)) return skipped("${dataSource.name}: ${target.path.joinToString(".")}", "in flight, loaded, or failed too recently")
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
        val queued = enqueue(Request(key, dataSource, task, file = null, claimed = false))
        // A refresh of the top level lists it, before any discovery queued after it: a name it does not hold is not listed for again.
        if (queued && path.isEmpty()) {
            inventoryOf(dataSource, model)
            inventories[dataSource.uniqueId]?.rootsListed = true
        }
        queued
    }.getOrElse { e ->
        LOG.warn("Refreshing $path of ${dataSource.name} failed", e)
        false
    }

    private fun wasLoaded(dataSource: LocalDataSource, target: NamespaceTarget): Boolean {
        val key = AutoIntrospectEngine.Key(dataSource.uniqueId, target.path)
        return !pathResolver.isChildless(target.node) || key in loaded || engine.state(key) == AutoIntrospectEngine.State.Done ||
            CatalogScopes.selects(dataSource.introspectionScope, profile.levels, target.path)
    }

    private fun enqueue(work: Work): Boolean =
        synchronized(workers) { workers.getOrPut(work.dataSource.uniqueId, ::Worker) }.offer(work)

    /** The namespace of [file]'s newest request, if any. */
    public fun pending(file: VirtualFile): String? =
        synchronized(banners) { banners.lastOrNull { it.file == file }?.label }

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
        private val queue = ArrayDeque<Work>()
        private var job: Job? = null

        fun offer(work: Work): Boolean = synchronized(this) {
            if (queue.size >= MAX_QUEUED) return false
            queue.addLast(work)
            if (job == null) {
                val started = scope.launch { drain() }
                job = started
                started.invokeOnCompletion { abandon(started) }
            }
            true
        }

        private suspend fun drain() {
            while (true) {
                when (val next = synchronized(this) { queue.removeFirstOrNull() ?: null.also { job = null } } ?: return) {
                    is Request -> refresh(next)
                    is Discovery -> discover(next)
                }
            }
        }

        /** Releases what a cancelled or failed worker left queued. */
        private fun abandon(ended: Job) {
            val left = synchronized(this) {
                if (job !== ended) return
                job = null
                queue.toList().also { queue.clear() }
            }
            left.forEach { work ->
                when (work) {
                    is Request -> finish(work, loaded = false)
                    is Discovery -> finish(work)
                }
            }
        }
    }

    private suspend fun refresh(request: Request) {
        var synced = false
        var loaded = false
        try {
            val dataSource = request.dataSource
            if (request.claimed) {
                dataSource.introspectionScope = CatalogScopes.widen(dataSource.introspectionScope, CatalogScopes.select(profile.levels, request.key.path))
                storageOf(dataSource)?.updateDataSource(dataSource)
            }
            // A namespace whose children are namespaces the dialect may list itself, cheaply, instead of the platform's refresh.
            val listed = if (request.claimed && request.key.path.size < profile.levels.size) list(dataSource, request.key.path, request.requested) else null
            synced = listed ?: sync(request)
            loaded = synced && (!request.claimed || readAction { isLoaded(request) })
            LOG.info("Auto-introspection ${if (loaded) "loaded" else if (synced) "found nothing in" else "failed to load"} " +
                "${request.dataSource.name}: ${request.label}")
        } finally {
            if (synced) this.synced += request.key else this.synced -= request.key
            finish(request, loaded)
        }
        if (loaded && request.key.path.isNotEmpty()) this.loaded += request.key
        // A failed refresh of the top level did not list it after all.
        if (!synced && request.key.path.isEmpty()) inventories[request.dataSource.uniqueId]?.rootsListed = false
        // Also after an empty result: what it was asked for is now known not to exist.
        if (synced) request.file?.let { highlightAgain(it) }
    }

    /**
     * Whether the namespace at [path] of [dataSource] was introspected, so that a missing name under it does
     * not exist: a refresh of this service ran on it without errors, or the introspection scope selects it
     * by name (a scope that lists every namespace but some, as a default may, selects none by name). Not
     * while a refresh runs or after one failed.
     */
    internal fun introspected(dataSource: LocalDataSource, path: List<String>): Boolean {
        val key = AutoIntrospectEngine.Key(dataSource.uniqueId, path)
        return when (engine.state(key)) {
            AutoIntrospectEngine.State.InFlight -> false
            AutoIntrospectEngine.State.Done -> true
            is AutoIntrospectEngine.State.Failed -> key in synced
            null -> key in synced || key in loaded || CatalogScopes.selectsByName(dataSource.introspectionScope, profile.levels, path)
        }
    }

    /**
     * Discovers [discovery]'s path a level at a time, in this worker so no other refresh of the data source
     * interleaves: at most one listing of the top level and one load per level, each decided anew from the
     * model, never a sync of the whole scope.
     */
    private suspend fun discover(discovery: Discovery) {
        var changed = false
        val label = "${discovery.dataSource.name}: ${discovery.label}"
        try {
            repeat(profile.levels.size + 1) {
                val step = readAction {
                    val file = psi(discovery.file) ?: return@readAction null
                    if (inTransaction(file)) {
                        skipped(label, "the console is in a transaction")
                        return@readAction null
                    }
                    val model = discovery.dataSource.model
                    inventoryOf(discovery.dataSource, model)
                    nextStep(file, discovery.dataSource, discovery.path)
                } ?: return
                when (step) {
                    Step.ListRoots -> {
                        val inventory = inventories[discovery.dataSource.uniqueId] ?: return
                        inventory.rootsListed = true
                        val listed = list(discovery.dataSource, emptyList(), discovery.path) ?: false
                        LOG.info("Auto-introspection ${if (listed) "listed" else "failed to list"} the top-level namespaces of ${discovery.dataSource.name}")
                        if (!listed) {
                            inventory.rootsListed = false
                            return
                        }
                        changed = true
                    }
                    is Step.Load -> {
                        val request = readAction {
                            val element = step.target.node as? BasicElement ?: return@readAction null
                            val task = prepareOneElementRefreshTask(discovery.dataSource.uniqueId, element) ?: return@readAction null
                            val key = AutoIntrospectEngine.Key(discovery.dataSource.uniqueId, step.target.path)
                            if (!engine.claim(key)) return@readAction null
                            Request(key, discovery.dataSource, task, file = null, claimed = true, requested = discovery.path)
                        } ?: run {
                            skipped(label, "${step.target.path.joinToString(".")} is in flight, loaded, or failed too recently")
                            return
                        }
                        refresh(request)
                        if (engine.state(request.key) != AutoIntrospectEngine.State.Done) return
                        changed = true
                    }
                    Step.Found -> return
                    Step.Unknown -> {
                        skipped(label, "not found after listing and loading what holds it")
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Discovering $label failed", e)
        } finally {
            finish(discovery)
            if (changed && !project.isDisposed) {
                highlightAgain(discovery.file)
                completeAgain(discovery.file)
            }
        }
    }

    private suspend fun list(dataSource: LocalDataSource, parent: List<String>, requested: List<String>): Boolean? = try {
        listNamespaces(dataSource, parent, requested)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LOG.warn("Listing the namespaces of ${dataSource.name} under $parent failed", e)
        false
    }

    /**
     * Lists the namespaces of [dataSource] under [parent] (empty: the top level) into its model, cheaply: names
     * only, nothing below them, for finding [requested] (the path as typed); true when it succeeded, null to leave
     * it to the platform. By default the top level is [listNamespaces] and a namespace's children the platform's
     * one-element refresh. A dialect may list them itself (Doris's `SHOW DATABASES FROM catalog`) on a connection
     * of its own, never a console's, and write the names; a listed namespace counts as loaded. Runs off the EDT, in
     * the data source's worker; the top level at most once after each clear of its model.
     */
    protected open suspend fun listNamespaces(dataSource: LocalDataSource, parent: List<String>, requested: List<String>): Boolean? =
        if (parent.isEmpty()) listNamespaces(dataSource) else null

    /**
     * Lists [dataSource]'s top-level namespaces into its model, cheaply: names only, nothing below them; true
     * when it succeeded. By default the platform's namespace listing; a dialect whose introspector lists them
     * poorly may query them itself (on a connection of its own, never a console's) and write the names. Runs
     * off the EDT, in the data source's worker, at most once after each clear of its model.
     */
    protected open suspend fun listNamespaces(dataSource: LocalDataSource): Boolean {
        val context = LoaderContext.selectListNamespacesTask(project, dataSource)
        val result = DataSourceSyncManager.getInstance().tryPerformSync(context, stopRunning = false, merge = false)
        return result != null && context.errorHandler?.hasErrors() != true
    }

    private fun finish(discovery: Discovery) {
        synchronized(banners) { banners -= discovery }
        if (!project.isDisposed) EditorNotifications.getInstance(project).updateNotifications(discovery.file)
    }

    private fun psi(file: VirtualFile): PsiFile? = file.takeIf { it.isValid && !project.isDisposed }?.let(PsiManager.getInstance(project)::findFile)

    /** Shows completion again in [file]'s selected editor when its caret still follows a qualified path. */
    private suspend fun completeAgain(file: VirtualFile) = withContext(Dispatchers.EDT) {
        if (project.isDisposed || !file.isValid) return@withContext
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@withContext
        if (FileDocumentManager.getInstance().getFile(editor.document) != file) return@withContext
        if (qualifiedPathBefore(editor.document.charsSequence, editor.caretModel.offset, profile.quoteChars) == null) return@withContext
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
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
