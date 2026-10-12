package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.database.util.Case
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.formatter.settings.SqlCodeStyles
import com.intellij.sql.psi.SqlBinaryExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax
import dev.brikk.house.sql.metadata.KeywordCatalog
import dev.brikk.house.sql.metadata.KeywordCategory
import dev.brikk.house.sql.metadata.MetadataSnapshot
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
    final override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC || !parameters.originalFile.language.isKindOf(profile.language)) return
        val reads = ProfileReads(profile, parameters.originalFile)
        val functions = completeFunctions(parameters, result, reads)
        completeTableFunctionArguments(parameters, result, reads)
        if (!result.isStopped) completeKeywords(parameters, result, reads, functions)
    }

    /**
     * Keywords that neither [functions] (upper-case names this contributor offered) nor a later
     * contributor offered. Keywords of later contributors are spelled in the same case as these:
     * the user's SQL code style keyword case if it sets one, else the profile's.
     */
    private fun completeKeywords(parameters: CompletionParameters, result: CompletionResultSet, reads: ProfileReads, functions: Set<String>) {
        val scope = profile.keywords
        val style = profile.styles[SqlLookupKind.KEYWORD]
        val codeStyleCase = SqlCodeStyles.getSqlSettings(parameters.originalFile).keywordCase.takeIf { it != Case.MIXED }
        val recased = style.case.takeIf { codeStyleCase == null && it != LookupCase.AS_GIVEN }
        if (scope == KeywordScope.NONE && recased == null) return
        val catalog = reads.keywords ?: return
        val offered = HashSet(functions)
        result.runRemainingContributors(parameters) { completion ->
            val element = completion.lookupElement
            offered += element.lookupString.uppercase()
            val keyword = recased?.takeIf { element.isKeywordOf(catalog) }?.let { CasedKeyword(element, it.apply(element.lookupString)) }
            result.passResult(keyword?.let(completion::withLookupElement) ?: completion)
        }
        if (scope == KeywordScope.NONE) return
        val fallback = if (codeStyleCase == null) style else style.copy(case = LookupCase.AS_GIVEN)
        val sink = result.caseInsensitive()
        for (keyword in catalog.keywords) {
            if (scope == KeywordScope.RESERVED && keyword.category != KeywordCategory.RESERVED) continue
            if (keyword.word.uppercase() in offered) continue
            val word = codeStyleCase?.apply(keyword.word) ?: style.case.apply(keyword.word)
            sink.addElement(fallback.lookup(word, word, style.typeText))
        }
    }
}

/**
 * Whether this item, from another contributor, is a keyword: a bold plain-text item whose words
 * are all in [catalog]. Identifiers, columns and functions carry their symbol as the item's object.
 */
private fun LookupElement.isKeywordOf(catalog: KeywordCatalog): Boolean {
    val text = lookupString
    return `object` == text &&
        text.split(' ').all { word -> word.none(Char::isLetter) || catalog[word] != null } &&
        LookupElementPresentation.renderElement(this).isItemTextBold
}

/** [delegate] spelled [text], keeping its insertion, matching on its other lookup strings, and priority. */
private class CasedKeyword(delegate: LookupElement, private val text: String) : LookupElementDecorator<LookupElement>(delegate) {
    override fun getLookupString(): String = text

    override fun getAllLookupStrings(): Set<String> = delegate.allLookupStrings - delegate.lookupString + text

    // The platform's case-insensitive keywords are inserted in the typed prefix's case.
    override fun isCaseSensitive(): Boolean = true

    override fun renderElement(presentation: LookupElementPresentation) {
        super.renderElement(presentation)
        presentation.itemText = text
    }
}

/** The profile's per-file hooks for one completion, each called at most once. */
private class ProfileReads(val profile: SqlCompletionProfile, val file: PsiFile) {
    val snapshot by lazy(LazyThreadSafetyMode.NONE) { profile.snapshot(file) }
    val functions by lazy(LazyThreadSafetyMode.NONE) { profile.functions(file) }
    val serverOnlyFunctions by lazy(LazyThreadSafetyMode.NONE) { profile.serverOnlyFunctions(file) }
    val keywords by lazy(LazyThreadSafetyMode.NONE) { profile.keywordCatalog(file, snapshot) }
}

/** Offers the functions the gates allow and returns their upper-case names. */
private fun completeFunctions(parameters: CompletionParameters, result: CompletionResultSet, reads: ProfileReads): Set<String> {
    val profile = reads.profile
    val file = parameters.originalFile
    val gates = profile.functionGates
    if (gates.digitPrefix && prefixIsDigitLed(file, parameters.offset)) return emptySet()
    if (parameters.invocationCount == 0 && gates.autopopup?.matches(file, parameters.offset) == false) return emptySet()
    val qualifier = PsiTreeUtil.getParentOfType(parameters.position, SqlReferenceExpression::class.java, false)
        ?.qualifierExpression?.text
    val sink = if (reads.snapshot.functions.caseSensitive) result else result.caseInsensitive()
    val names = HashSet<String>()
    fun offer(entry: FunctionPresentation, serverOnly: Boolean) {
        sink.addElement(profile.lookup(entry, serverOnly))
        names += entry.name.uppercase()
    }
    val rule = gates.qualifier
    if (qualifier != null && rule !is QualifierRule.Unrestricted) {
        if (rule is QualifierRule.Namespaced) rule.functions(qualifier, file).forEach { offer(it, serverOnly = false) }
        return names
    }
    reads.functions.forEach { offer(it, serverOnly = false) }
    reads.serverOnlyFunctions.forEach { offer(it, serverOnly = true) }
    return names
}

/**
 * Property names inside the parentheses of a table function taking `"key" = "value"`
 * properties, and the known values on the right of `=`. Both are strings, so the prefix runs from
 * the opening quote to the caret; outside a string the item is inserted quoted.
 */
private fun completeTableFunctionArguments(parameters: CompletionParameters, result: CompletionResultSet, reads: ProfileReads) {
    val position = parameters.position
    val offset = parameters.offset
    val profile = reads.profile
    val (call, function) = tableFunctionCallAt(reads.snapshot, position, offset) ?: return
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

private class QuotingInsertHandler(private val quote: Char) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val text = "$quote${item.lookupString}$quote"
        context.document.replaceString(context.startOffset, context.tailOffset, text)
        context.editor.caretModel.moveToOffset(context.startOffset + text.length)
    }
}

private val QUOTES = charArrayOf('\'', '"', '`')

/** The innermost call of a known table function whose parentheses hold [offset], walking up from [position]. */
internal fun tableFunctionCallAt(snapshot: MetadataSnapshot, position: PsiElement, offset: Int): Pair<SqlFunctionCallExpression, TableFunctionInfo>? {
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
