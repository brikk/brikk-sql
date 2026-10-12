package dev.brikk.house.sql.intellij.editor

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.database.sql.common.impl.editor.SqlQuoteHandler
import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.sql.psi.SqlTokens
import org.jetbrains.annotations.ApiStatus

/**
 * Hides the substrate grammar's parse errors in files of [language]: they describe the platform
 * grammar, not the dialect. Register a no-arg subclass as a `highlightErrorFilter`.
 */
@ApiStatus.Experimental
public abstract class SubstrateErrorFilter(private val language: Language) : HighlightErrorFilter() {
    final override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean =
        element.containingFile?.language?.isKindOf(language) != true
}

/**
 * The quote handler of a dialect. Enter inside a quoted string inserts a newline, or, with
 * [EnterInString.Split], splits it into a `||` concatenation. Spans a masking lexer collapsed into
 * one string token are never split. Register a no-arg subclass as the language's `lang.quoteHandler`.
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
            element.text.startsWith('\'')
}
