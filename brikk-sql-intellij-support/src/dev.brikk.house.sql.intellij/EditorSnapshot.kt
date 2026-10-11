package dev.brikk.house.sql.intellij

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.util.concurrent.CancellationException

/** Exact document identity/revision. A later edit-and-revert also invalidates work. */
class EditorSnapshot private constructor(val document: Document, val text: String, val revision: Long) {
    /** Throws [SqlExecutionRefusal] for stale input; cancellation propagates unchanged. */
    fun requireCurrent() {
        ProgressManager.checkCanceled()
        if (document.modificationStamp != revision || document.text != text) {
            throw SqlExecutionRefusal("SQL changed during preparation. Run the current statement again.")
        }
    }
    companion object {
        fun capture(document: Document): EditorSnapshot = EditorSnapshot(document, document.text, document.modificationStamp)
    }
}

/** Optional editor recovery must not swallow cancellation or fatal JVM errors. */
inline fun <T> recoverEditorOperation(operation: () -> T): Result<T> = try {
    ProgressManager.checkCanceled()
    Result.success(operation())
} catch (e: ProcessCanceledException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
