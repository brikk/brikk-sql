package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import org.jetbrains.annotations.ApiStatus

/**
 * Autopopup decisions for the profile's language. With [SqlFunctionGates.digitPrefix] the
 * autopopup is skipped after a digit-led prefix, where no name can begin. Elsewhere it leaves
 * the decision to the next confidence. Register it with `order="first"` through a no-arg subclass.
 */
@ApiStatus.Experimental
public abstract class SqlCompletionConfidence(private val profile: SqlCompletionProfile) : CompletionConfidence() {
    final override fun shouldSkipAutopopup(contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState = when {
        !psiFile.language.isKindOf(profile.language) -> ThreeState.UNSURE
        profile.functionGates.digitPrefix && prefixIsDigitLed(psiFile, offset) -> ThreeState.YES
        else -> ThreeState.UNSURE
    }
}
