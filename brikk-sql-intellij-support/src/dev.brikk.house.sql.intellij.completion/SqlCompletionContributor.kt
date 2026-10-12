package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.util.ProcessingContext
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax
import org.jetbrains.annotations.ApiStatus

/**
 * Completes the profile's functions from metadata.
 * Register it for the dialect's language with a no-arg subclass.
 */
@ApiStatus.Experimental
public abstract class SqlCompletionContributor(profile: SqlCompletionProfile) : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), FunctionProvider(profile))
    }
}

private abstract class ProfileProvider(protected val profile: SqlCompletionProfile) : CompletionProvider<CompletionParameters>() {
    final override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        if (parameters.originalFile.language.isKindOf(profile.language)) complete(parameters, result)
    }

    abstract fun complete(parameters: CompletionParameters, result: CompletionResultSet)
}

private class FunctionProvider(profile: SqlCompletionProfile) : ProfileProvider(profile) {
    override fun complete(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        val gates = profile.functionGates
        if (gates.digitPrefix && prefixIsDigitLed(file, parameters.offset)) return
        if (parameters.invocationCount == 0 && gates.autopopup?.matches(file, parameters.offset) == false) return
        val qualifier = PsiTreeUtil.getParentOfType(parameters.position, SqlReferenceExpression::class.java, false)
            ?.qualifierExpression?.text
        val styles = profile.styles
        val sink = if (profile.snapshot(file).functions.caseSensitive) result else result.caseInsensitive()
        val rule = gates.qualifier
        if (qualifier != null && rule !is QualifierRule.Unrestricted) {
            if (rule is QualifierRule.Namespaced) rule.functions(qualifier, file).forEach { sink.addElement(styles.function(it, serverOnly = false)) }
            return
        }
        profile.functions(file).forEach { sink.addElement(styles.function(it, serverOnly = false)) }
        profile.serverOnlyFunctions(file).forEach { sink.addElement(styles.function(it, serverOnly = true)) }
    }
}

private fun SqlLookupStyles.function(entry: FunctionPresentation, serverOnly: Boolean): LookupElement {
    val style = this[entry.lookupKind]
    val typeText = when {
        serverOnly -> this[SqlLookupKind.SERVER_FUNCTION].typeText
        entry.extension != null -> this[SqlLookupKind.EXTENSION_FUNCTION].typeText(entry.extension.orEmpty())
        style.showsReturnType && entry.returnType != null -> entry.returnType
        else -> style.typeText
    }
    val tailText = entry.signature?.takeIf { style.showsSignature }
        ?.let { "($it)" + if (entry.overloadCount > 1) "  +${entry.overloadCount - 1}" else "" }
    return style.lookup(entry, entry.name, typeText, tailText, entry.insertHandler)
}

private val FunctionPresentation.lookupKind: SqlLookupKind
    get() = when (kind) {
        FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING ->
            if (nativeKind == "table_macro") SqlLookupKind.TABLE_MACRO else SqlLookupKind.TABLE_FUNCTION
        FunctionKind.AGGREGATE -> SqlLookupKind.AGGREGATE
        FunctionKind.WINDOW -> SqlLookupKind.WINDOW
        FunctionKind.SCALAR -> if (nativeKind == "macro") SqlLookupKind.MACRO else SqlLookupKind.FUNCTION
    }

/** Bare where the grammar accepts the bare name, a call where it accepts one, otherwise (a special form) only the name. */
private val FunctionPresentation.insertHandler: InsertHandler<LookupElement>?
    get() = when {
        InvocationSyntax.BARE in syntaxes -> null
        InvocationSyntax.CALL in syntaxes -> if (overloadCount == 1 && signature == "") CallParentheses.NO_ARGUMENTS else CallParentheses.ARGUMENTS
        else -> null
    }

/** Inserts `()` after the name, the caret inside unless the only overload takes no arguments; an existing `(` is reused. */
private enum class CallParentheses(private val caretInside: Boolean) : InsertHandler<LookupElement> {
    ARGUMENTS(true),
    NO_ARGUMENTS(false),
    ;

    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        if (context.completionChar == '(') context.setAddCompletionChar(false)
        val tail = context.tailOffset
        val document = context.document
        val caret = if (tail < document.textLength && document.charsSequence[tail] == '(') {
            tail + 1
        } else {
            document.insertString(tail, "()")
            if (caretInside) tail + 1 else tail + 2
        }
        context.editor.caretModel.moveToOffset(caret)
    }
}

/** The identifier or number run ending at [offset], read from the document so a completion dummy does not matter. */
private fun prefixAt(file: PsiFile, offset: Int): Pair<Int, Int> {
    val text = file.viewProvider.contents
    val end = offset.coerceIn(0, text.length)
    var start = end
    while (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it == '_' }) start--
    return start to end
}

internal fun prefixIsDigitLed(file: PsiFile, offset: Int): Boolean {
    val (start, end) = prefixAt(file, offset)
    return start < end && file.viewProvider.contents[start].isDigit()
}

private fun ExpressionPositions.matches(file: PsiFile, offset: Int): Boolean {
    val start = prefixAt(file, offset).first
    if (start <= 0) return false
    var leaf: PsiElement? = file.findElementAt(start - 1)
    while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment || leaf.textLength == 0)) {
        leaf = PsiTreeUtil.prevLeaf(leaf, true)
    }
    val text = leaf?.text ?: return false
    return text in operators || keywords.any { it.equals(text, ignoreCase = true) }
}
