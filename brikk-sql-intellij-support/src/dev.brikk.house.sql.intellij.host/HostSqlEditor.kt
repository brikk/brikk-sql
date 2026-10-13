package dev.brikk.house.sql.intellij.host

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.Icon
import javax.swing.JComponent
import org.jetbrains.annotations.ApiStatus

/**
 * A gutter icon on each host string [channel] claims, named [name] in Settings | Editor | General |
 * Gutter Icons so users can turn it off. Pending and unavailable strings show the icon dimmed. The
 * tooltip is the caller's. Register a no-arg subclass as a `codeInsight.lineMarkerProvider` for the
 * host language.
 */
@ApiStatus.Experimental
public abstract class HostSqlLineMarkerProvider(
    private val channel: HostSqlChannel,
    private val name: String,
    private val icon: Icon,
) : LineMarkerProviderDescriptor() {
    /** The tooltip of a claimed host string in [state]. */
    protected abstract fun tooltip(state: HostSqlState): String

    final override fun getName(): String = name

    final override fun getIcon(): Icon = icon

    final override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null
        val host = hostStartingAt(element) ?: return null
        // Injection is lazy: inject the host now, so its state is current.
        InjectedLanguageManager.getInstance(host.project).getInjectedPsiFiles(host)
        val state = channel.state(host) ?: return null
        val dimmed = state.claim is HostSqlClaim.Unavailable || (state.claim as? HostSqlClaim.Injected)?.pending == true
        val text = tooltip(state)
        return LineMarkerInfo(element, element.textRange, if (dimmed) IconLoader.getDisabledIcon(icon) else icon, { text }, null, GutterIconRenderer.Alignment.LEFT) { text }
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
