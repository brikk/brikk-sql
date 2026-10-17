package dev.brikk.house.sql.intellij

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.annotations.ApiStatus

/** Notifies plain-text [title] and [content]: markup is escaped and line breaks, CRLF included, are kept. */
@ApiStatus.Experimental
public fun notifyEscaped(
    project: Project?,
    groupId: String,
    title: String,
    content: String,
    type: NotificationType = NotificationType.ERROR,
) {
    Notification(groupId, escaped(title), escaped(content), type).notify(project)
}

private fun escaped(text: String): String =
    StringUtil.escapeXmlEntities(StringUtil.convertLineSeparators(text)).replace("\n", "<br>")
