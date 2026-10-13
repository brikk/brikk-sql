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
import com.intellij.database.model.DasRoutine
import com.intellij.database.util.Case
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.formatter.settings.SqlCodeStyles
import com.intellij.sql.psi.SqlBinaryExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlResolveResult
import dev.brikk.house.sql.intellij.catalog.isBuiltinCatalogObject
import dev.brikk.house.sql.intellij.pipe.PipeText
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
        val file = parameters.originalFile
        // Injected SQL is read unescaped, so a host's doubled quotes do not change its literals.
        val source = PipeText.of(file)
        val before = source.text.substring(0, source.textOffset(prefixAt(file, parameters.offset).first).coerceIn(0, source.text.length))
        val context = sourceContext(before, profile.metadata.dialect, ProgressManager::checkCanceled)
        val position = functionPosition(
            context?.statement ?: before, profile.metadata.dialect, profile.tablePositions, profile.stagePhrases,
            { name -> name in reads.snapshot.functions }, ProgressManager::checkCanceled,
        )
        val inCode = context?.inCode != false
        val functions = if (inCode) completeFunctions(parameters, result, reads, position, context) else emptySet()
        completeTableFunctionArguments(parameters, result, reads)
        if (!result.isStopped) completeKeywords(parameters, result, reads, functions, position.takeIf { inCode })
    }

    /**
     * Passes on later contributors' items, dropping the base dialect's builtin-function items for
     * [functions] (upper-case names this contributor offered from metadata), so each is offered once;
     * the base dialect's own forms stay. Then keywords that neither [functions] nor a later
     * contributor offered; none outside code (a null [position]). Keywords of later contributors are
     * spelled in the same case as these: the user's SQL code style keyword case if it sets one, else
     * the profile's. A PIPE stage head gets none of its own: only a stage phrase stands there.
     */
    private fun completeKeywords(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        reads: ProfileReads,
        functions: Set<String>,
        position: FunctionPosition?,
    ) {
        val scope = profile.keywords
        val style = profile.styles[SqlLookupKind.KEYWORD]
        val codeStyleCase = SqlCodeStyles.getSqlSettings(parameters.originalFile).keywordCase.takeIf { it != Case.MIXED }
        val recased = style.case.takeIf { codeStyleCase == null && it != LookupCase.AS_GIVEN }
        val catalog = if (scope != KeywordScope.NONE || recased != null) reads.keywords else null
        if (catalog == null && functions.isEmpty()) return
        val offered = HashSet(functions)
        result.runRemainingContributors(parameters) { completion ->
            val element = completion.lookupElement
            if (element.isBaseBuiltin() && element.lookupString.uppercase() in functions) return@runRemainingContributors
            offered += element.lookupString.uppercase()
            val keyword = recased?.takeIf { catalog != null && element.isKeywordOf(catalog) }?.let { CasedKeyword(element, it.apply(element.lookupString)) }
            result.passResult(keyword?.let(completion::withLookupElement) ?: completion)
        }
        if (catalog == null || scope == KeywordScope.NONE || position == null || position == FunctionPosition.STAGE_HEAD) return
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
 * Whether this item, from another contributor, is one of the base dialect's builtin functions: a
 * prototype, or a routine of the platform's builtin catalog. A data source's or the user's routine
 * of the same name is never one.
 */
private fun LookupElement.isBaseBuiltin(): Boolean = when (val target = `object`) {
    is BuiltinFunction.Prototype -> true
    is SqlResolveResult -> target.targetSymbol?.dasObject.let { it is DasRoutine && isBuiltinCatalogObject(it) }
    else -> false
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

/**
 * Offers the functions the gates allow that fit [position] and returns their upper-case names. The
 * gates read [context], the source's tokens, or PSI where core does not know the dialect.
 */
private fun completeFunctions(
    parameters: CompletionParameters,
    result: CompletionResultSet,
    reads: ProfileReads,
    position: FunctionPosition,
    context: SourceContext?,
): Set<String> {
    val profile = reads.profile
    val file = parameters.originalFile
    val gates = profile.functionGates
    if (gates.digitPrefix && prefixIsDigitLed(file, parameters.offset)) return emptySet()
    val autopopup = gates.autopopup
    if (parameters.invocationCount == 0 && autopopup != null) {
        val follows = if (context != null) autopopup.follows(context.previous) else autopopup.matches(file, parameters.offset)
        if (!follows) return emptySet()
    }
    val qualifier = if (context != null) context.qualifier
    else PsiTreeUtil.getParentOfType(parameters.position, SqlReferenceExpression::class.java, false)?.qualifierExpression?.text
    val sink = if (reads.snapshot.functions.caseSensitive) result else result.caseInsensitive()
    val names = HashSet<String>()
    val positions = profile.tablePositions
    fun offer(entry: FunctionPresentation, serverOnly: Boolean) {
        if (!position.offers(entry, positions)) return
        val wrapper = positions.wrapper?.takeIf { position == FunctionPosition.TABLE && positions.wraps(entry) }
        sink.addElement(profile.lookup(entry, serverOnly, wrapper))
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

/** The item for [entry]; with [wrapper], inserted as `wrapper(name(...))`. */
private fun SqlCompletionProfile.lookup(entry: FunctionPresentation, serverOnly: Boolean, wrapper: String? = null): LookupElement {
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
    val insertHandler = entry.insertHandler
    return style.lookup(entry, entry.name, typeText, tailText, wrapper?.let { Wrapped(it, insertHandler) } ?: insertHandler)
}

/**
 * Writes [wrapper] and `(` before the name and `)` after its call, as Trino's `TABLE(sequence(...))`.
 * An existing call keeps its arguments, with the caret inside; one whose `)` cannot be found keeps
 * only [call]'s insertion.
 */
private class Wrapped(private val wrapper: String, private val call: InsertHandler<LookupElement>?) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val start = context.startOffset
        val tail = context.tailOffset
        val text = document.charsSequence
        if (tail < text.length && text[tail] == '(') {
            val close = closingParen(text, tail)
            if (close == null) {
                call?.handleInsert(context, item)
                return
            }
            document.insertString(close + 1, ")")
        } else {
            document.insertString(tail, "())")
        }
        document.insertString(start, "$wrapper(")
        if (context.completionChar == '(') context.setAddCompletionChar(false)
        context.editor.caretModel.moveToOffset(tail + wrapper.length + 2)
    }
}

/** The `)` closing the `(` at [open], skipping quoted text; null when the text ends first. */
private fun closingParen(text: CharSequence, open: Int): Int? {
    var depth = 0
    var quote: Char? = null
    for (index in open until text.length) {
        val char = text[index]
        when {
            quote != null -> if (char == quote) quote = null
            char == '\'' || char == '"' || char == '`' -> quote = char
            char == '(' -> depth++
            char == ')' -> if (--depth == 0) return index
        }
    }
    return null
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
    return follows(leaf?.text)
}

/** Whether a function may follow [previous], the last token before the typed name. */
private fun ExpressionPositions.follows(previous: String?): Boolean =
    previous != null && (previous in operators || keywords.any { it.equals(previous, ignoreCase = true) })
