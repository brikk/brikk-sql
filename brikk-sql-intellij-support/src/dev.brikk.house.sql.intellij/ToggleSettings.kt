package dev.brikk.house.sql.intellij

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.util.xmlb.annotations.OptionTag
import org.jetbrains.annotations.ApiStatus

/**
 * A persisted project switch for a feature of [language]. Changing it reparses that language's
 * files, including those open only in non-file editors, and restarts highlighting. The concrete
 * class carries `@Service` and `@State(name = "...", storages = [Storage(ToggleSettings.STORAGE)])`:
 * every plugin's switch lives in the project's workspace file, and another storage is reported.
 */
@ApiStatus.Experimental
public abstract class ToggleSettings(private val project: Project, private val language: Language) :
    SerializablePersistentStateComponent<ToggleSettings.Options>(Options()) {

    // XMLB skips final scalar fields without an explicit field annotation.
    public data class Options(@field:OptionTag("enabled") @JvmField val enabled: Boolean = false)

    @Volatile
    private var initialized = false

    public var enabled: Boolean
        get() = state.enabled
        set(value) {
            if (value == state.enabled) return
            updateState { it.copy(enabled = value) }
            changed(value)
        }

    /** Runs after [enabled] changes, before files refresh. */
    protected open fun onChanged(enabled: Boolean) {}

    final override fun initializeComponent() {
        initialized = true
        val storages = javaClass.getAnnotation(State::class.java)?.storages?.map { it.value } ?: return
        if (storages != listOf(STORAGE)) {
            thisLogger().error("${javaClass.name} must be stored in ToggleSettings.STORAGE ($STORAGE), not $storages")
        }
    }

    final override fun loadState(state: Options) {
        val changed = state.enabled != this.state.enabled
        super.loadState(state)
        // Initial loading can happen while a parser requests this service.
        if (initialized && changed) changed(state.enabled)
    }

    public companion object {
        /** Where every toggle is stored: the project's workspace file, per user and not shared through VCS. */
        public const val STORAGE: String = StoragePathMacros.WORKSPACE_FILE
    }

    private fun changed(enabled: Boolean) {
        onChanged(enabled)
        ApplicationManager.getApplication().invokeLater({
            val documents = PsiDocumentManager.getInstance(project)
            documents.commitAllDocuments()
            val psi = PsiManagerEx.getInstanceEx(project)
            val files = buildSet {
                psi.fileManager.allCachedFiles.forEach { add(it.viewProvider.virtualFile) }
                addAll(FileEditorManager.getInstance(project).openFiles)
                for (editor in EditorFactory.getInstance().allEditors) {
                    if (editor.project === project) FileDocumentManager.getInstance().getFile(editor.document)?.let(::add)
                }
            }.filter { it.isValid && psi.findFile(it)?.language?.isKindOf(language) == true }
            // Restarting the daemon alone would keep setting-dependent PSI.
            documents.reparseFiles(files, false)
            DaemonCodeAnalyzer.getInstance(project).restart(this@ToggleSettings)
        }, project.disposed)
    }
}
