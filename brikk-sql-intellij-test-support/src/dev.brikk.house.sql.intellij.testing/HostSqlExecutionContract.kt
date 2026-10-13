package dev.brikk.house.sql.intellij.testing

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor
import org.jetbrains.annotations.ApiStatus

/**
 * Execution contract of SQL a [PipeDialect] injects into host strings (its `hostSql`): Execute from
 * the host's own editor (a Kotlin file, say), with the caret in inspection-only SQL, is refused with
 * a warning and never reaches the stock action, which would run the injected SQL in a console.
 * [openHost] opens a host file of the plugin's host language whose only string is claimed
 * inspection-only PIPE SQL, and returns its editor with the caret inside that SQL. Call it from a
 * light platform test; a failed check throws [AssertionError].
 */
@ApiStatus.Experimental
public class HostSqlExecutionContract(private val dialect: PipeDialect, private val openHost: () -> Editor) {
    /** With the caret in the SQL, Execute is refused; with the caret outside every string, it goes to the stock action. */
    public fun refusesInspectionOnlySqlFromTheHostEditor() {
        val editor = openHost()
        val project = requireNotNull(editor.project) { "The editor needs a project" }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val file = requireNotNull(PsiDocumentManager.getInstance(project).getPsiFile(editor.document))
        val inside = editor.caretModel.offset
        val notifications = mutableListOf<Notification>()
        val connection = project.messageBus.connect()
        connection.subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId != dialect.notificationGroup) return
                notification.setSuppressShowingPopup(true)
                notifications += notification
            }
        })
        try {
            fun execute(): Boolean {
                var delegated = false
                val stock = object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        delegated = true
                    }
                }
                val event = AnActionEvent.createEvent(
                    SimpleDataContext.builder()
                        .add(CommonDataKeys.PROJECT, project)
                        .add(CommonDataKeys.EDITOR, editor)
                        .add(CommonDataKeys.PSI_FILE, file)
                        .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                        .build(),
                    Presentation(), "HostSqlExecutionContract", ActionUiKind.NONE, null,
                )
                CooperativeExecuteAction(1, stock, PipeExecuteInterceptor(dialect)).actionPerformed(event)
                return delegated
            }
            expect(!execute()) { "Execute with the caret in inspection-only SQL reached the stock action" }
            expect(notifications.singleOrNull()?.type == NotificationType.WARNING) { "The refusal must be one warning: ${notifications.map { it.content }}" }
            editor.caretModel.moveToOffset(0)
            expect(execute()) { "Execute outside every string must go to the stock action" }
            editor.caretModel.moveToOffset(inside)
        } finally {
            connection.disconnect()
            notifications.forEach { it.expire() }
        }
    }

    private fun expect(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }
}
