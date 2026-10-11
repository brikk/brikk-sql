package dev.brikk.house.sql.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorSnapshotTest : BasePlatformTestCase() {
    private fun Document.edit(change: Document.() -> Unit) = WriteCommandAction.runWriteCommandAction(project) { change() }

    fun testEditIsRefused() {
        val document = EditorFactory.getInstance().createDocument("SELECT 1")
        val snapshot = EditorSnapshot.capture(document)
        snapshot.requireCurrent()
        document.edit { insertString(0, " ") }
        assertThrows(SqlExecutionRefusal::class.java) { snapshot.requireCurrent() }
    }

    fun testEditAndRevertIsRefused() {
        val document = EditorFactory.getInstance().createDocument("SELECT 1")
        val snapshot = EditorSnapshot.capture(document)
        document.edit {
            insertString(0, " ")
            deleteString(0, 1)
        }
        assertEquals(snapshot.text, document.text)
        assertThrows(SqlExecutionRefusal::class.java) { snapshot.requireCurrent() }
    }

    fun testCancellationPropagatesBeforeStaleness() {
        val document = EditorFactory.getInstance().createDocument("SELECT 1")
        val snapshot = EditorSnapshot.capture(document)
        document.edit { insertString(0, " ") }
        val indicator = EmptyProgressIndicator()
        assertThrows(ProcessCanceledException::class.java) {
            ProgressManager.getInstance().runProcess({
                indicator.cancel()
                snapshot.requireCurrent()
            }, indicator)
        }
    }
}
