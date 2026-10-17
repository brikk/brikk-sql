package dev.brikk.house.sql.intellij.host

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.database.intentions.RunQueryIntentionActionAvailable
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.brikk.house.sql.intellij.pipe.injectedFiles
import java.util.function.Function
import javax.swing.Icon
import javax.swing.JComponent
import org.jetbrains.annotations.ApiStatus

/**
 * A gutter icon on each host string [channel] claims, named [name] in Settings | Editor | General |
 * Gutter Icons so users can turn it off. Pending and unavailable strings, and every string while the
 * IDE indexes, show the icon dimmed. The tooltip is the caller's. Register a no-arg subclass as a
 * `codeInsight.lineMarkerProvider` for the host language.
 */
@ApiStatus.Experimental
public abstract class HostSqlLineMarkerProvider(
    private val channel: HostSqlChannel,
    private val name: String,
    private val icon: Icon,
) : LineMarkerProviderDescriptor(), DumbAware {
    /** The tooltip of a claimed host string in [state]. */
    protected abstract fun tooltip(state: HostSqlState): String

    final override fun getName(): String = name

    final override fun getIcon(): Icon = icon

    // Markers need the host's injection, which is not cheap: they are all slow ones.
    final override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    final override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        for (element in elements) {
            if (element.firstChild != null) continue
            val host = hostStartingAt(element) ?: continue
            InjectedLanguageManager.getInstance(host.project).getInjectedPsiFiles(host)
            val state = channel.state(host) ?: continue
            val dimmed = DumbService.isDumb(host.project) || state.claim is HostSqlClaim.Unavailable ||
                (state.claim as? HostSqlClaim.Injected)?.pending == true
            val text = tooltip(state)
            result += LineMarkerInfo(element, element.textRange, if (dimmed) IconLoader.getDisabledIcon(icon) else icon, { text }, null, GutterIconRenderer.Alignment.LEFT) { text }
        }
    }

    /** The injection host whose first leaf [leaf] is. */
    private fun hostStartingAt(leaf: PsiElement): PsiLanguageInjectionHost? {
        var element = leaf
        while (true) {
            if (element is PsiLanguageInjectionHost) return element
            val parent = element.parent ?: return null
            if (PsiTreeUtil.getDeepestFirst(parent) !== leaf) return null
            element = parent
        }
    }
}

/**
 * An editor banner shown only when [actionNeeded] reports something the user must do (a helper that
 * is missing or too old, say), with a link that fixes it; never a routine notice. Register a no-arg
 * subclass as an `editorNotificationProvider`.
 */
@ApiStatus.Experimental
public abstract class HostSqlActionBanner : EditorNotificationProvider, DumbAware {
    /** What [file] needs: a [message] and the [fixText] of a link running [fix]. */
    public data class Action(val message: String, val fixText: String, val fix: Runnable)

    /** The action [file] needs in [project], or null for no banner. */
    protected abstract fun actionNeeded(project: Project, file: VirtualFile): Action?

    final override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val action = actionNeeded(project, file) ?: return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = action.message
                createActionLabel(action.fixText, action.fix)
            }
        }
    }
}

/**
 * Vetoes the platform's "Run query in console" intention, which runs injected SQL without the
 * Execute action, for SQL [channel] says is for editing only: the file's own, or any injected into a
 * host string the editor's selection intersects or its caret touches. Register a no-arg subclass as
 * `com.intellij.database.runConsoleAvailable`.
 */
@ApiStatus.Experimental
public abstract class HostSqlRunConsoleVeto(private val channel: HostSqlChannel) : RunQueryIntentionActionAvailable {
    final override fun isAvailable(editor: Editor, file: PsiFile): Boolean = !inspectionOnly(editor, file)

    final override fun isAvailable(editor: Editor): Boolean {
        val file = editor.project?.let { PsiDocumentManager.getInstance(it).getPsiFile(editor.document) } ?: return true
        return !inspectionOnly(editor, file)
    }

    private fun inspectionOnly(editor: Editor, file: PsiFile): Boolean {
        if (channel.isInspectionOnly(file)) return true
        val host = InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file) ?: file
        val hostEditor = (editor as? EditorWindow)?.delegate ?: editor
        if (host !== file && hostEditor === editor) return false
        return injectedFiles(host, hostEditor.caretModel.offset, hostEditor.selectionModel).any(channel::isInspectionOnly)
    }
}
