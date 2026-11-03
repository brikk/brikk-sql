package dev.brikk.house.sql.intellij.dialect

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.diagnostics.datafusionFunctionDiagnostics
import dev.brikk.house.sql.intellij.pipe.PipeText

/** Native evidence checks. No engine or model calls, no arity guessed from introspection examples. */
public abstract class DatafusionFunctionAnnotator(private val profile: DatafusionEditorProfile) : Annotator {
    final override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        if (file.language != profile.language) return
        val source = PipeText.of(file)
        val snapshot = profile.snapshot(file)
        val exact = profile.serverVersion(file)?.let { version -> profile.metadata.forServerVersion(version).let {
            it.exact && it.snapshot.engineVersion == snapshot.engineVersion
        } } == true
        for (problem in datafusionFunctionDiagnostics(source.text, profile.dialect, snapshot, ProgressManager::checkCanceled)) {
            val range = source.fileRange(problem.start, problem.end) ?: continue
            val reference = file.findElementAt(range.startOffset)?.let { PsiTreeUtil.getParentOfType(it, SqlReferenceExpression::class.java, false) }
            val target = reference?.resolveSymbol()
            if (target != null && target !is BuiltinFunction) continue
            holder.newAnnotation(if (exact) HighlightSeverity.ERROR else HighlightSeverity.WARNING,
                if (exact) problem.message else "Bundled ${snapshot.engineVersion}: ${problem.message}").range(range).create()
        }
    }
}
