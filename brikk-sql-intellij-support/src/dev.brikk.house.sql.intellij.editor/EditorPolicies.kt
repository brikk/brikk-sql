package dev.brikk.house.sql.intellij.editor

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.database.sql.common.impl.editor.SqlQuoteHandler
import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlElementTypes
import com.intellij.sql.psi.SqlTokens
import org.jetbrains.annotations.ApiStatus

/**
 * Hides the substrate grammar's parse errors in files of [language]. Register a no-arg subclass as
 * a `highlightErrorFilter`.
 *
 * With [SubstrateErrors.ALL] (a foreign substrate, whose grammar is not the dialect's) every error
 * is hidden. With [SubstrateErrors.LENIENT_ONLY] (a stock substrate, such as a ClickHouse family on
 * the platform ClickHouse grammar, whose errors are the dialect's) only errors inside a lenient
 * fallback are: a top-level generic `SQL_STATEMENT` holding tokens and parameters but no grammar
 * structure, as `parseLenientStatement` and the boundary gates build.
 */
@ApiStatus.Experimental
public abstract class SubstrateErrorFilter(
    private val language: Language,
    private val scope: SubstrateErrors = SubstrateErrors.ALL,
) : HighlightErrorFilter() {
    final override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        if (element.containingFile?.language?.isKindOf(language) != true) return true
        return when (scope) {
            SubstrateErrors.ALL -> false
            SubstrateErrors.LENIENT_ONLY -> generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }.none(::isLenientFallback)
        }
    }
}

/** Which substrate errors [SubstrateErrorFilter] hides. */
@ApiStatus.Experimental
public enum class SubstrateErrors {
    /** Every one: the substrate grammar is not the dialect's. */
    ALL,

    /** Only those inside lenient fallback statements: the substrate grammar is the dialect's. */
    LENIENT_ONLY,
}

/** A top-level generic statement of tokens, parameters and errors only: what lenient parsing builds. */
private fun isLenientFallback(element: PsiElement): Boolean =
    element.parent is PsiFile && element.node.elementType == SqlCompositeElementTypes.SQL_STATEMENT &&
        generateSequence(element.firstChild) { it.nextSibling }.all { child ->
            child.firstChild == null || child is PsiErrorElement || child.node.elementType == SqlElementTypes.SQL_PARAMETER_REFERENCE
        }

/**
 * The quote handler of a dialect. Enter inside a quoted string inserts a newline, or, with
 * [EnterInString.Split], splits it into a `||` concatenation; prefixed strings such as `E'..'`
 * split like plain ones. Spans a masking lexer collapsed into one string token are never split.
 * Register a no-arg subclass as the language's `lang.quoteHandler`.
 */
@ApiStatus.Experimental
public abstract class SqlDialectQuoteHandler(private val enter: EnterInString) : SqlQuoteHandler.Concat() {
    public enum class EnterInString {
        /** A newline inside the literal, for dialects whose strings span lines. */
        Newline,

        /** `'ab' || 'cd'` around the caret. */
        Split,
    }

    final override fun canBeConcatenated(element: PsiElement): Boolean =
        enter == EnterInString.Split && element.node?.elementType == SqlTokens.SQL_STRING_TOKEN &&
            quoteAfterPrefix(element.text) == '\''
}
