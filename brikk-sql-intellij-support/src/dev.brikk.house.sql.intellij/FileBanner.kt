package dev.brikk.house.sql.intellij

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/** Per-file banner messages that live and die with their project. Keep one instance per banner. */
public class FileBanner(id: String) {
    private val key = Key.create<ConcurrentHashMap<VirtualFile, String>>(id)

    public fun message(project: Project, file: VirtualFile): String? = project.getUserData(key)?.get(file)

    public fun show(project: Project, file: VirtualFile, message: String) {
        if (messages(project).put(file, message) != message) EditorNotifications.getInstance(project).updateNotifications(file)
    }

    public fun clear(project: Project, file: VirtualFile) {
        if (project.getUserData(key)?.remove(file) != null) EditorNotifications.getInstance(project).updateNotifications(file)
    }

    private fun messages(project: Project): ConcurrentHashMap<VirtualFile, String> = synchronized(this) {
        project.getUserData(key) ?: ConcurrentHashMap<VirtualFile, String>().also { project.putUserData(key, it) }
    }
}

/** Shows [banner]'s message above the file's editors, with a Dismiss link. Register a no-arg subclass. */
public abstract class FileBannerProvider(
    private val banner: FileBanner,
    private val status: EditorNotificationPanel.Status = EditorNotificationPanel.Status.Info,
) : EditorNotificationProvider {
    final override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val message = banner.message(project, file) ?: return null
        return Function {
            EditorNotificationPanel(status).apply {
                text = message
                createActionLabel("Dismiss") { banner.clear(project, file) }
            }
        }
    }
}
