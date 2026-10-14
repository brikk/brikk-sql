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
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.parser.parseOne
import org.jetbrains.annotations.ApiStatus

/**
 * Hides the substrate grammar's parse errors in files of [language]. Register a no-arg subclass as
 * a `highlightErrorFilter`.
 *
 * With [SubstrateErrors.ALL] (a foreign substrate, whose grammar is not the dialect's) every error
 * is hidden. With [SubstrateErrors.LENIENT_ONLY] (a stock substrate, such as a ClickHouse family on
 * the platform ClickHouse grammar, whose errors are the dialect's) only errors inside a lenient
 * fallback are: a top-level generic `SQL_STATEMENT` holding tokens and parameters but no grammar
 * structure, as `parseLenientStatement` and the boundary gates build; and, given a [coreDialect], those
 * in a statement core parses cleanly, which the grammar rejects only where it lacks a construct (such as
 * a query argument the platform parses lazily, after the boundary gate). Core's own parse reports real
 * errors, through the native-error annotator's local parse.
 */
@ApiStatus.Experimental
public abstract class SubstrateErrorFilter(
    private val language: Language,
    private val scope: SubstrateErrors = SubstrateErrors.ALL,
    private val coreDialect: String? = null,
) : HighlightErrorFilter() {
    // Binary compatibility (AGENTS.md): the constructor from before coreDialect.
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public constructor(language: Language, scope: SubstrateErrors = SubstrateErrors.ALL) : this(language, scope, null)

    final override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        if (element.containingFile?.language?.isKindOf(language) != true) return true
        return when (scope) {
            SubstrateErrors.ALL -> false
            SubstrateErrors.LENIENT_ONLY -> {
                val enclosing = generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }.toList()
                enclosing.none(::isLenientFallback) && enclosing.lastOrNull()?.let { coreParses(it) } != true
            }
        }
    }

    /** Whether core parses [statement], a top-level statement, as one it models, without errors. */
    private fun coreParses(statement: PsiElement): Boolean {
        val dialect = coreDialect ?: return false
        return CachedValuesManager.getCachedValue(statement) {
            val parsed = runCatching { parseOne(statement.text, dialect) }.getOrNull()
            CachedValueProvider.Result.create(parsed != null && parsed !is Command, statement)
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
