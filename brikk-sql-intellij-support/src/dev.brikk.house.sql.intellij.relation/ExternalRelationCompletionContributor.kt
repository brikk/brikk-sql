package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.database.model.DasColumn
import com.intellij.database.symbols.DasSymbol
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlAsExpression
import com.intellij.sql.psi.SqlNameElement
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlResolveResult
import dev.brikk.house.sql.intellij.SqlColumnCompletion
import dev.brikk.house.sql.intellij.SqlColumnFacts
import dev.brikk.house.sql.intellij.catalog.dasTablePath
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.completion.lookup
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.CompletionItem
import dev.brikk.house.sql.scope.PositionKind
import dev.brikk.house.sql.scope.ReferenceKind
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.ScopeColumn
import org.jetbrains.annotations.ApiStatus

/**
 * Completes the columns the analysis sees at the caret, create-or-decorate (and at a FROM position
 * the provider relations the platform does not know, such as slots): another contributor's item
 * standing for a visible column (the platform's lookup of a light symbol or of the native adapter's
 * column, a data source column of the same table, a declaration or pass-through in the SQL) is
 * decorated with the column's label, and only columns no item stands for are created, in their
 * label's lookup kind; nothing is offered twice, and columns of different relations stay distinct.
 * Labels come from the providers' or the profile's [RelationColumnLabels]. Register a no-arg
 * subclass for the language with `order="first"`.
 */
@ApiStatus.Experimental
public abstract class ExternalRelationCompletionContributor(private val profile: RelationScopeProfile) : CompletionContributor(), DumbAware {
    final override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile
        val analysis = RelationAnalysis.of(file, profile) ?: return
        val text = analysis.source.text
        val offset = analysis.source.textOffset(parameters.offset).coerceIn(0, text.length)
        val statement = SqlScriptSnapshot.parse(text, profile.dialect, ProgressManager::checkCanceled).at(offset)
            ?.let { it.start until maxOf(it.end, offset) } ?: return
        val completion = RelationScope.complete(text, profile.dialect, analysis.facts.merged, offset, statement, ProgressManager::checkCanceled)
        val sink = result.withPrefixMatcher(completion.prefix).caseInsensitive()
        when (completion.position) {
            PositionKind.COLUMN -> completeColumns(parameters, sink, completion.items, analysis)
            PositionKind.RELATION -> completeRelations(sink, completion.items, analysis)
            else -> {}
        }
    }

    /**
     * The provider relations SQL can name here that the platform does not know (slots, table
     * functions), inserted as core writes them (`src()`); CTEs and a data source's tables are the
     * platform's own items.
     */
    private fun completeRelations(sink: CompletionResultSet, items: List<CompletionItem>, analysis: RelationAnalysis) {
        val style = profile.styles[SqlLookupKind.TABLE]
        for (item in items) {
            val relation = item.relation ?: continue
            if (!analysis.manages(relation)) continue
            sink.addElement(inserting(style.lookup(item.name, item.detail), item))
        }
    }

    /**
     * The columns of [items], create-or-decorate. Even with none, the provider columns other items stand
     * for and the analysis does not see here are hidden: it places every provider column.
     */
    private fun completeColumns(parameters: CompletionParameters, sink: CompletionResultSet, items: List<CompletionItem>, analysis: RelationAnalysis) {
        val candidates = LinkedHashMap<Identity, Candidate>()
        for (item in items) {
            val column = item.column ?: continue
            val label = analysis.labels(column).label(column)
            val identity = Identity(column.name, column.relation, column.source?.relation, column.provenance)
            val facts = SqlColumnFacts(column.name, label.type, label.nullable, label.relation, label.origin, identity)
            candidates.putIfAbsent(identity, Candidate(item, column, facts, label, analysis.providerOf(column)))
        }
        val byFacts = candidates.values.associateBy { it.facts }
        SqlColumnCompletion.createOrDecorate(
            parameters,
            sink,
            candidates.values.map { it.facts },
            create = { facts -> byFacts.getValue(facts).let { inserting(SqlColumnCompletion.create(facts, profile.styles[it.label.kind]), it.item) } },
            // A provider column no visible column matches is not visible here as itself: an outer or shadowed
            // relation's, or one a set operation or USING merges into a column of its own.
            hides = { symbolOf(it) != null },
        ) { element -> match(element, candidates.values, analysis, parameters)?.facts }
    }

    /** Which column a candidate is: its name, the exact label of the relation showing it, and the fact behind it. */
    private data class Identity(val name: String, val relation: String, val source: String?, val provenance: String?)

    private class Candidate(
        val item: CompletionItem,
        val column: ScopeColumn,
        val facts: SqlColumnFacts,
        val label: RelationColumnLabel,
        val provider: ExternalRelationFacts?,
    )

    /** The provider column [element] stands for, as the resolve extension or the native adapter gives it. */
    private fun symbolOf(element: LookupElement): RelationColumnSymbol? =
        ((element.`object` as? SqlResolveResult)?.targetSymbol ?: element.`object`) as? RelationColumnSymbol ?: element.psiElement as? RelationColumnSymbol

    /** The visible column another contributor's [element] stands for, or null. */
    private fun match(element: LookupElement, candidates: Collection<Candidate>, analysis: RelationAnalysis, parameters: CompletionParameters): Candidate? {
        val target = (element.`object` as? SqlResolveResult)?.targetSymbol ?: element.`object` ?: element.psiElement
        // A light symbol, of the resolve extension or the native adapter: the same fact, under the same relation if it says.
        symbolOf(element)?.let { symbol ->
            val same = candidates.filter { it.column.source == symbol.column.source && it.column.name == symbol.column.name }
            // The relation the item shows the column under: its qualifier (a self-join's `e` or `f`), else the symbol's own.
            val relation = ((element.`object` as? SqlResolveResult)?.qualifier as? SqlNameElement)?.name ?: symbol.column.relation
            return same.firstOrNull { it.column.relation == relation } ?: same.singleOrNull()
        }
        val das = ((target as? DasSymbol)?.dasObject ?: target) as? DasColumn
        if (das != null) {
            val table = das.table?.let(::dasTablePath) ?: return null
            return candidates.filter {
                it.column.origin == ColumnOrigin.PHYSICAL_TABLE && it.column.source?.relation == table && it.column.name == das.name
            }.singleOrNull()
        }
        val declaration = target as? PsiElement ?: element.psiElement ?: return null
        val file = parameters.originalFile
        if (declaration.containingFile?.originalFile != file) {
            // A provider's declaration, as the native adapter gives its columns.
            return candidates.filter { candidate ->
                candidate.column.name.equals(element.lookupString, ignoreCase = true) &&
                    candidate.column.provenance?.let { candidate.provider?.navigationTarget(file, it) } == declaration
            }.singleOrNull()
        }
        // A declaration in the SQL (an alias, a CTE or derived table's column): the platform offers it from the completion copy.
        val named = (declaration as? PsiNameIdentifierOwner)?.nameIdentifier ?: (declaration as? SqlReferenceExpression)?.identifier ?: declaration
        val range = named.textRange ?: return null
        val start = analysis.source.textOffset(originalOffset(range.startOffset, parameters))
        val end = analysis.source.textOffset(originalOffset(range.endOffset, parameters))
        candidates.filter { candidate -> candidate.column.declaration?.let { it.start >= start && it.end <= end } == true }.singleOrNull()?.let { return it }
        // A column passed on, as it is or renamed: the platform's item is the inner reference or its alias, whose
        // reference core resolves to the same fact.
        val aliased = (PsiTreeUtil.getParentOfType(declaration, SqlAsExpression::class.java, false)?.expression as? SqlReferenceExpression)?.identifier
        val inner = listOfNotNull(start to end, aliased?.textRange?.let { analysis.source.textOffset(originalOffset(it.startOffset, parameters)) to analysis.source.textOffset(originalOffset(it.endOffset, parameters)) })
            .flatMap { (from, to) -> analysis.referencesAt(from, to) }.filter { it.kind == ReferenceKind.COLUMN }.flatMap { it.columns }
        return candidates.filter { candidate ->
            candidate.column.source != null && candidate.column.name.equals(element.lookupString, ignoreCase = true) &&
                inner.any { it.source == candidate.column.source }
        }.singleOrNull()
    }

    /** The original file's offset of [offset] in the completion copy, which holds a dummy identifier at the caret. */
    private fun originalOffset(offset: Int, parameters: CompletionParameters): Int =
        if (offset <= parameters.offset) offset else offset - (parameters.position.containingFile.textLength - parameters.originalFile.textLength)

    /** [element] inserting the analysis's text for [item] (quoted where it must be, a slot as `src()`) when that differs from the name. */
    private fun inserting(element: LookupElement, item: CompletionItem): LookupElement =
        if (item.insertText == item.name) element
        else object : LookupElementDecorator<LookupElement>(element) {
            override fun handleInsert(context: InsertionContext) {
                context.document.replaceString(context.startOffset, context.tailOffset, item.insertText)
                context.editor.caretModel.moveToOffset(context.startOffset + item.insertText.length)
            }
        }
}
