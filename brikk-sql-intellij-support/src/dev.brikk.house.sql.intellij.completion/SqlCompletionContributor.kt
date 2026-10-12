package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlBinaryExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.util.ProcessingContext
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax
import dev.brikk.house.sql.metadata.KeywordCategory
import dev.brikk.house.sql.metadata.TableFunctionInfo
import org.jetbrains.annotations.ApiStatus

/**
 * Completes the profile's functions, keywords and table-function properties from metadata.
 * It reads no indexes, so it also completes while they are built. Register it for the dialect's
 * language with a no-arg subclass and `order="first"`: it runs the contributors after it and
 * offers only the keywords none of them offered.
 */
@ApiStatus.Experimental
public abstract class SqlCompletionContributor(private val profile: SqlCompletionProfile) : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), FunctionProvider(profile))
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), TableFunctionArgumentProvider(profile))
    }

    final override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        super.fillCompletionVariants(parameters, result)
        if (parameters.completionType != CompletionType.BASIC || result.isStopped || !parameters.originalFile.language.isKindOf(profile.language)) return
        val scope = profile.keywords
        if (scope == KeywordScope.NONE) return
        val keywords = profile.snapshot(parameters.originalFile).keywords?.keywords ?: return
        val offered = HashSet<String>()
        result.runRemainingContributors(parameters) { completion ->
            offered += completion.lookupElement.lookupString.uppercase()
            result.passResult(completion)
        }
        val style = profile.styles[SqlLookupKind.KEYWORD]
        val sink = result.caseInsensitive()
        for (keyword in keywords) {
            if (scope == KeywordScope.RESERVED && keyword.category != KeywordCategory.RESERVED) continue
            if (keyword.word.uppercase() in offered) continue
            val word = style.case.apply(keyword.word)
            sink.addElement(style.lookup(word, word, style.typeText))
        }
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
        val sink = if (profile.snapshot(file).functions.caseSensitive) result else result.caseInsensitive()
        val rule = gates.qualifier
        if (qualifier != null && rule !is QualifierRule.Unrestricted) {
            if (rule is QualifierRule.Namespaced) rule.functions(qualifier, file).forEach { sink.addElement(profile.lookup(it, serverOnly = false)) }
            return
        }
        profile.functions(file).forEach { sink.addElement(profile.lookup(it, serverOnly = false)) }
        profile.serverOnlyFunctions(file).forEach { sink.addElement(profile.lookup(it, serverOnly = true)) }
    }
}

/**
 * Property names inside the parentheses of a table function taking `"key" = "value"`
 * properties, and the known values on the right of `=`. Both are strings, so the prefix runs from
 * the opening quote to the caret; outside a string the item is inserted quoted.
 */
private class TableFunctionArgumentProvider(profile: SqlCompletionProfile) : ProfileProvider(profile) {
    override fun complete(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val offset = parameters.offset
        val (call, function) = profile.tableFunctionCallAt(parameters.originalFile, position, offset) ?: return
        val binary = PsiTreeUtil.getParentOfType(position, SqlBinaryExpression::class.java)?.takeIf { call.textRange.contains(it.textRange) }
        val key = binary?.lOperand?.takeIf { left ->
            offset > left.textRange.endOffset &&
                '=' in binary.text.substring(0, (offset - binary.textRange.startOffset).coerceIn(0, binary.textLength))
        }?.text?.trim(*QUOTES)
        val (kind, candidates) = if (key == null) {
            SqlLookupKind.TABLE_FUNCTION_PROPERTY to function.properties.map { it.name }
        } else {
            SqlLookupKind.TABLE_FUNCTION_VALUE to function.properties.firstOrNull { it.name.equals(key, ignoreCase = true) }?.values.orEmpty()
        }
        if (candidates.isEmpty()) return
        val typed = position.text.orEmpty().take((offset - position.textRange.startOffset).coerceAtLeast(0))
        val quoted = typed.firstOrNull()?.let { it in QUOTES } == true
        val prefix = if (quoted) typed.drop(1) else typed.takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
        val sink = result.withPrefixMatcher(prefix).caseInsensitive()
        val style = profile.styles[kind]
        val insert = if (quoted) null else QuotingInsertHandler(profile.propertyQuote)
        for (name in candidates) sink.addElement(style.lookup(name, name, style.typeText(function.name), insertHandler = insert))
    }
}

private class QuotingInsertHandler(private val quote: Char) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val text = "$quote${item.lookupString}$quote"
        context.document.replaceString(context.startOffset, context.tailOffset, text)
        context.editor.caretModel.moveToOffset(context.startOffset + text.length)
    }
}

private val QUOTES = charArrayOf('\'', '"', '`')

/** The innermost call of a known table function whose parentheses hold [offset], walking up from [position]. */
internal fun SqlCompletionProfile.tableFunctionCallAt(file: PsiFile, position: PsiElement, offset: Int): Pair<SqlFunctionCallExpression, TableFunctionInfo>? {
    val snapshot = snapshot(file)
    if (snapshot.tableFunctions.isNullOrEmpty()) return null
    var call = PsiTreeUtil.getParentOfType(position, SqlFunctionCallExpression::class.java, false)
    while (call != null) {
        // With empty parentheses the caret leaf can be `)` or an error element, so "after the `(`" decides.
        val paren = call.text.indexOf('(')
        if (paren >= 0 && offset > call.textRange.startOffset + paren) {
            call.nameElement?.name?.let(snapshot::tableFunction)?.let { return call to it }
        }
        call = PsiTreeUtil.getParentOfType(call, SqlFunctionCallExpression::class.java)
    }
    return null
}

private fun SqlCompletionProfile.lookup(entry: FunctionPresentation, serverOnly: Boolean): LookupElement {
    val style = styles[lookupKind(entry)]
    val extension = entry.extension
    val typeText = when {
        serverOnly -> styles[SqlLookupKind.SERVER_FUNCTION].typeText
        extension != null -> styles[SqlLookupKind.EXTENSION_FUNCTION].typeText(extension)
        style.showsReturnType && entry.returnType != null -> entry.returnType
        else -> style.typeText
    }
    val tailText = entry.signature?.takeIf { style.showsSignature }
        ?.let { "($it)" + if (entry.overloadCount > 1) "  +${entry.overloadCount - 1}" else "" }
    return style.lookup(entry, entry.name, typeText, tailText, entry.insertHandler)
}

private fun SqlCompletionProfile.lookupKind(entry: FunctionPresentation): SqlLookupKind =
    entry.nativeKind?.let(nativeKinds::get) ?: when (entry.kind) {
        FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING -> SqlLookupKind.TABLE_FUNCTION
        FunctionKind.AGGREGATE -> SqlLookupKind.AGGREGATE
        FunctionKind.WINDOW -> SqlLookupKind.WINDOW
        FunctionKind.SCALAR -> SqlLookupKind.FUNCTION
    }

/** Names written with parentheses, as a call or a special form, get them; a bare-only name stays bare. */
private val FunctionPresentation.insertHandler: InsertHandler<LookupElement>?
    get() = if (InvocationSyntax.CALL in syntaxes || InvocationSyntax.SPECIAL_FORM in syntaxes) {
        Parentheses(insertsByDefault = InvocationSyntax.BARE !in syntaxes && InvocationSyntax.CALL in syntaxes, argumentless = overloadCount == 1 && signature == "")
    } else {
        null
    }

/**
 * An existing `(` after the name is reused, with the caret inside it. Otherwise `()` is inserted
 * for a call that is not also accepted bare, or when `(` was typed to accept the item; the caret
 * goes inside, or after `()` when the only overload takes no arguments.
 */
private class Parentheses(private val insertsByDefault: Boolean, private val argumentless: Boolean) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val tail = context.tailOffset
        val typed = context.completionChar == '('
        val caret = when {
            tail < document.textLength && document.charsSequence[tail] == '(' -> tail + 1
            typed || insertsByDefault -> {
                document.insertString(tail, "()")
                if (argumentless) tail + 2 else tail + 1
            }
            else -> return
        }
        if (typed) context.setAddCompletionChar(false)
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
