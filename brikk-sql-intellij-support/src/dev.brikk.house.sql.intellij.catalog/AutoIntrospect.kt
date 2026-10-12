package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.introspection.prepareOneElementRefreshTask
import com.intellij.database.model.basic.BasicElement
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

    private class Pending(val file: VirtualFile, val namespace: String)

    private val pending = LinkedHashMap<Any, Pending>()

    /**
     * Introspects the namespace [path] names in [file] (as typed, absolute or relative to the console's
     * namespace) when it is listed but childless. True when a refresh started.
     */
    public fun requestPath(file: PsiFile, path: List<String>): Boolean {
        val profile = profile
        if (!profile.autoIntrospect) return false
        return recoverEditorOperation {
            val dataSource = profile.dataSources.resolve(file).dataSource ?: return@recoverEditorOperation false
            val model = DbPsiFacade.getInstance(project).findDataSource(dataSource.uniqueId)?.model ?: return@recoverEditorOperation false
            val current = if (profile.usesCurrentNamespace) profile.dataSources.consoleNamespace(file) else emptyList()
            val resolver = profile.pathResolver
            val target = resolver.resolve(model.modelRoots.toList(), path, current) ?: return@recoverEditorOperation false
            resolver.isChildless(target.node) && request(file, dataSource, target)
        }.getOrDefault(false)
    }

    /** Introspects [target] of [dataSource] for [file], unless it is claimed. True when a refresh started. */
    public fun request(file: PsiFile, dataSource: LocalDataSource, target: NamespaceTarget): Boolean {
        if (!profile.autoIntrospect) return false
        val element = target.node as? BasicElement ?: return false
        val task = prepareOneElementRefreshTask(dataSource.uniqueId, element) ?: return false
        val key = AutoIntrospectEngine.Key(dataSource.uniqueId, target.path)
        if (!engine.claim(key)) return false
        val host = file.originalFile.viewProvider.virtualFile
        val id = Any()
        synchronized(pending) { pending[id] = Pending(host, target.path.joinToString(".")) }
        EditorNotifications.getInstance(project).updateNotifications(host)
        val job = scope.launch {
            var succeeded = false
            try {
                dataSource.introspectionScope = CatalogScopes.widen(dataSource.introspectionScope, CatalogScopes.select(profile.levels, target.path))
                storageOf(dataSource)?.updateDataSource(dataSource)
                val context = LoaderContext.selectTask(project, dataSource, task)
                // Queued, not stopping a running refresh: a second namespace typed meanwhile must not cancel the first.
                DataSourceSyncManager.getInstance().tryPerformSync(context, stopRunning = false, merge = false)
                succeeded = context.errorHandler?.hasErrors() != true
                if (succeeded) highlightAgain(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Introspecting ${key.path} of ${dataSource.name} failed", e)
            } finally {
                engine.finish(key, succeeded)
            }
        }
        job.invokeOnCompletion {
            synchronized(pending) { pending.remove(id) }
            if (!project.isDisposed) EditorNotifications.getInstance(project).updateNotifications(host)
        }
        return true
    }

    /** The namespace of [file]'s newest running request, if any. */
    public fun pending(file: VirtualFile): String? = synchronized(pending) { pending.values.lastOrNull { it.file == file }?.namespace }

    /** Hides [file]'s banner; the refreshes go on. */
    public fun dismiss(file: VirtualFile) {
        synchronized(pending) { pending.values.removeAll { it.file == file } }
        EditorNotifications.getInstance(project).updateNotifications(file)
    }

    private fun storageOf(dataSource: LocalDataSource): DataSourceStorage? =
        listOfNotNull(DataSourceStorage.getProjectStorage(project), DataSourceStorage.getStorage()).firstOrNull { dataSource in it.ownDataSources }

    private suspend fun highlightAgain(file: VirtualFile) {
        val psi = readAction { if (project.isDisposed || !file.isValid) null else PsiManager.getInstance(project).findFile(file) } ?: return
        DaemonCodeAnalyzer.getInstance(project).restart(psi, "introspected")
    }

    private companion object {
        val LOG = logger<AutoIntrospectService>()
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
