package dev.brikk.house.sql.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.util.concurrent.CancellationException
import org.jetbrains.annotations.ApiStatus

/** Exact document identity/revision. A later edit-and-revert also invalidates work. */
public class EditorSnapshot private constructor(public val document: Document, public val text: String, public val revision: Long) {
    /** Throws [SqlExecutionRefusal] for stale input; cancellation propagates unchanged. */
    public fun requireCurrent() {
        ProgressManager.checkCanceled()
        if (document.modificationStamp != revision || document.text != text) throw SqlExecutionRefusal(STALE_SQL)
    }

    public companion object {
        public fun capture(document: Document): EditorSnapshot = EditorSnapshot(document, document.text, document.modificationStamp)

        /**
         * Saving can reset a document's modification stamp without an edit. Call this before a save
         * and the returned function after it: the function throws [SqlExecutionRefusal] if the
         * document was edited since this call, even back to the same text, and otherwise captures
         * the saved revision. Edit tracking ends with [parent].
         */
        @ApiStatus.Experimental
        public fun captureAfterSave(document: Document, parent: Disposable): () -> EditorSnapshot {
            var edited = false
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    edited = true
                }
            }, parent)
            return {
                if (edited) throw SqlExecutionRefusal(STALE_SQL)
                capture(document)
            }
        }
    }
}

internal const val STALE_SQL: String = "SQL changed during preparation. Run the current statement again."

/** Optional editor recovery must not swallow cancellation or fatal JVM errors. */
public inline fun <T> recoverEditorOperation(operation: () -> T): Result<T> = try {
    ProgressManager.checkCanceled()
    Result.success(operation())
} catch (e: ProcessCanceledException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * Whether [editor] shows [file] itself in [project]: both belong to it and the editor's document is the file's (a
 * host editor with its host file, an injected editor with its injected file). An editor popup entry reads the
 * caret only then, never against another file's text.
 */
internal fun editorShowsFile(project: Project, editor: Editor, file: PsiFile): Boolean =
    editor.project == project && file.project == project && PsiDocumentManager.getInstance(project).getDocument(file) == editor.document
