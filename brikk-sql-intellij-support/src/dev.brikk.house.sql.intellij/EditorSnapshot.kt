package dev.brikk.house.sql.intellij

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.util.concurrent.CancellationException

/** Exact document identity/revision. A later edit-and-revert also invalidates work. */
public class EditorSnapshot private constructor(public val document: Document, public val text: String, public val revision: Long) {
    /** Throws [SqlExecutionRefusal] for stale input; cancellation propagates unchanged. */
    public fun requireCurrent() {
        ProgressManager.checkCanceled()
        if (document.modificationStamp != revision || document.text != text) {
            throw SqlExecutionRefusal("SQL changed during preparation. Run the current statement again.")
        }
    }
    public companion object {
        public fun capture(document: Document): EditorSnapshot = EditorSnapshot(document, document.text, document.modificationStamp)
    }
}

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
