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
import com.intellij.sql.psi.SqlResolveResult
import dev.brikk.house.sql.intellij.SqlColumnCompletion
import dev.brikk.house.sql.intellij.SqlColumnFacts
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.completion.lookup
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.CompletionItem
import dev.brikk.house.sql.scope.PositionKind
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.ScopeColumn
import org.jetbrains.annotations.ApiStatus

/**
 * Completes the columns the analysis sees at the caret, create-or-decorate (and at a FROM position
 * the provider relations the platform does not know, such as slots): another contributor's
 * item for a visible column (the platform's lookup of a light symbol, a data source column, a
 * declaration in the SQL) is decorated with the column's label, and only columns no item stands for
 * are created, so nothing is offered twice; same-named columns of different relations stay
 * distinct. Labels come from the providers' or the profile's [RelationColumnLabels]. Register a
 * no-arg subclass for the language with `order="first"`.
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
            PositionKind.COLUMN -> completeColumns(parameters, result, sink, completion.items, analysis)
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

    private fun completeColumns(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        sink: CompletionResultSet,
        items: List<CompletionItem>,
        analysis: RelationAnalysis,
    ) {
        val visible = LinkedHashMap<Identity, Candidate>()
        for (item in items) {
            val column = item.column ?: continue
            val label = analysis.labels(column).label(column)
            val identity = Identity(column.name, column.relation.lowercase(), column.source?.relation)
            visible.putIfAbsent(identity, Candidate(identity, item, column, SqlColumnFacts(column.name, label.type, label.nullable, label.relation, label.origin, identity), label))
        }
        if (visible.isEmpty()) return
        val offered = HashSet<Identity>()
        val platformNames = HashSet<String>()
        result.runRemainingContributors(parameters) { other ->
            val element = other.lookupElement
            val candidate = match(element, visible.values, analysis, parameters)
            when {
                candidate == null -> {
                    platformNames += element.lookupString.lowercase()
                    result.passResult(other)
                }
                offered.add(candidate.identity) -> result.passResult(other.withLookupElement(SqlColumnCompletion.decorate(element, candidate.facts)))
            }
        }
        for (candidate in visible.values) {
            if (candidate.identity in offered) continue
            // A column the platform resolves itself (a CTE's, a table's) and already offers under its name.
            if (!analysis.manages(candidate.column) && candidate.column.name.lowercase() in platformNames) continue
            offered += candidate.identity
            sink.addElement(inserting(SqlColumnCompletion.create(candidate.facts, profile.styles[candidate.label.kind]), candidate.item))
        }
    }

    private data class Identity(val name: String, val relation: String, val source: String?)

    private class Candidate(val identity: Identity, val item: CompletionItem, val column: ScopeColumn, val facts: SqlColumnFacts, val label: RelationColumnLabel)

    /** The visible column another contributor's [element] stands for, or null. */
    private fun match(element: LookupElement, visible: Collection<Candidate>, analysis: RelationAnalysis, parameters: CompletionParameters): Candidate? {
        val target = (element.`object` as? SqlResolveResult)?.targetSymbol ?: element.`object` ?: element.psiElement
        (target as? RelationColumnSymbol ?: element.psiElement as? RelationColumnSymbol)?.let { symbol ->
            return visible.firstOrNull { it.column == symbol.column }
        }
        val das = ((target as? DasSymbol)?.dasObject ?: target) as? DasColumn
        if (das != null) {
            val table = das.table?.name ?: return null
            return visible.filter { candidate ->
                val column = candidate.column
                column.origin == ColumnOrigin.PHYSICAL_TABLE && column.name.equals(das.name, ignoreCase = true) &&
                    column.source?.relation?.substringAfterLast('.').equals(table, ignoreCase = true)
            }.singleOrNull()
        }
        // A declaration in the SQL (an alias, a CTE column): the platform offers it from the completion copy.
        val declaration = target as? PsiElement ?: element.psiElement ?: return null
        val name = ((declaration as? PsiNameIdentifierOwner)?.nameIdentifier ?: declaration).textRange
        if (declaration.containingFile?.originalFile != parameters.originalFile) return null
        val start = analysis.source.textOffset(originalOffset(name.startOffset, parameters))
        val end = analysis.source.textOffset(originalOffset(name.endOffset, parameters))
        return visible.filter { candidate -> candidate.column.declaration?.let { it.start >= start && it.end <= end } == true }.singleOrNull()
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
