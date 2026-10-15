package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import dev.brikk.house.sql.docs.sqlPropertyAt
import org.jetbrains.annotations.ApiStatus

/**
 * Autopopup decisions for the profile's language. Inside the parentheses of a known table
 * function or a statement's property list the docs describe, where properties are strings, the
 * autopopup is never skipped. With
 * [SqlFunctionGates.digitPrefix] it is skipped after a digit-led prefix, where no name can
 * begin. Elsewhere it leaves the decision to the next confidence. Register it with
 * `order="first"` through a no-arg subclass.
 */
@ApiStatus.Experimental
public abstract class SqlCompletionConfidence(private val profile: SqlCompletionProfile) : CompletionConfidence(), DumbAware {
    final override fun shouldSkipAutopopup(contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState = when {
        !psiFile.language.isKindOf(profile.language) -> ThreeState.UNSURE
        tableFunctionCallAt(profile.snapshot(psiFile), contextElement, offset) != null -> ThreeState.NO
        profile.metadata.docs?.let { sqlPropertyAt(psiFile.text, it, offset, checkCanceled = ProgressManager::checkCanceled) } != null -> ThreeState.NO
        profile.functionGates.digitPrefix && prefixIsDigitLed(psiFile, offset) -> ThreeState.YES
        else -> ThreeState.UNSURE
    }
}
