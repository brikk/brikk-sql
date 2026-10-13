package dev.brikk.house.sql.intellij.relation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlAsExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.ReferenceKind
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationKind
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.ScopeColumn
import dev.brikk.house.sql.scope.ScopeReference
import dev.brikk.house.sql.scope.ScopeRelation
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The relation scope of one file's SQL (unescaped when injected) at one document revision, over
 * the profile's merged facts: each statement analyzed on its own, so one that does not parse
 * leaves the others. Offsets are in [source]'s text.
 */
internal class RelationAnalysis(
    val profile: RelationScopeProfile,
    val source: PipeText,
    val facts: MergedFacts,
    val statements: List<Pair<IntRange, RelationScope>>,
) {
    private val byRange: Map<Pair<Int, Int>, List<ScopeReference>> by lazy {
        statements.flatMap { it.second.references }.groupBy { it.start to it.end }
    }

    private val diagnosed: Set<Pair<Int, Int>> by lazy {
        statements.flatMapTo(HashSet()) { (_, scope) -> scope.diagnostics.map { it.start to it.end } }
    }

    /** The references naming exactly `[start, end)`. */
    fun referencesAt(start: Int, end: Int): List<ScopeReference> = byRange[start to end].orEmpty()

    /** Whether the analysis reports a problem on exactly `[start, end)`. */
    fun diagnoses(start: Int, end: Int): Boolean = (start to end) in diagnosed

    /** The innermost reference covering [offset] of the text, or the one ending there. */
    fun referenceAt(offset: Int): ScopeReference? = statementOf(offset)?.second?.referenceAt(offset)

    /** The statement holding [offset], its end included; statements are in text order. */
    private fun statementOf(offset: Int): Pair<IntRange, RelationScope>? {
        val after = statements.binarySearch { (range, _) -> if (range.first > offset) 1 else if (range.last + 1 < offset) -1 else 0 }
        return statements.getOrNull(after)
    }

    /**
     * The kind of the relation [column] is visible through at [offset]: a qualifier or table naming
     * it in [site] (the statement as completion sees it) or the statement, else, given [file], the
     * alias that names it in the statement's PSI. Null when unknown, such as a PIPE stage's output.
     */
    fun relationKind(column: ScopeColumn, offset: Int, file: PsiFile? = null, site: RelationScope? = null): RelationKind? {
        val label = column.relation.ifEmpty { return null }
        val (range, scope) = statementOf(offset) ?: return null
        for (one in listOfNotNull(site, scope)) {
            val named = one.references.filter { it.kind != ReferenceKind.COLUMN }.mapNotNull { it.relation }
            named.filter { it.name == label }.map { it.kind }.distinct().singleOrNull()?.let { return it }
        }
        return file?.let { aliasKind(it, range, label, column) }
    }

    private val aliasKinds = ConcurrentHashMap<Triple<Int, String, String>, Optional<RelationKind>>()

    /**
     * What the alias [label] of [column]'s relation names in the statement at [range] of [file]: a
     * provider's slot or call, a table, or something else (a CTE, a subquery); null when unknown.
     */
    private fun aliasKind(file: PsiFile, range: IntRange, label: String, column: ScopeColumn): RelationKind? {
        val key = column.source?.relation ?: return null
        return aliasKinds.getOrPut(Triple(range.first, label, key)) {
            val host = source.fileRange(range.first, range.last + 1)
            val root = host?.let { PsiTreeUtil.findCommonParent(file.findElementAt(it.startOffset), file.findElementAt(maxOf(it.startOffset, it.endOffset - 1))) }
            val kinds = PsiTreeUtil.findChildrenOfType(root ?: file, SqlAsExpression::class.java).filter { alias ->
                alias.name.equals(label, ignoreCase = true) && source.textOffset(alias.textRange.startOffset) in range
            }.map { alias ->
                when (val expression = alias.expression) {
                    is SqlFunctionCallExpression -> when {
                        expression.nameElement?.name != key -> RelationKind.OTHER
                        expression.parameterList?.expressionList.isNullOrEmpty() -> RelationKind.SLOT
                        else -> RelationKind.CALL
                    }
                    is SqlReferenceExpression -> if (key.substringAfterLast('.') == expression.name) RelationKind.TABLE else RelationKind.OTHER
                    else -> RelationKind.OTHER
                }
            }.distinct()
            Optional.ofNullable(kinds.singleOrNull())
        }.orElse(null)
    }

    /** The provider of [column]'s fact, by the kind of relation it is visible through, else by the first kind its key has. */
    fun providerOf(column: ScopeColumn, offset: Int, file: PsiFile? = null, site: RelationScope? = null): ExternalRelationFacts? {
        val key = column.source?.relation ?: return null
        val kind = relationKind(column, offset, file, site)?.takeIf { it in PROVIDED }
        return if (kind != null) facts.providerOf(kind, key) else PROVIDED.firstNotNullOfOrNull { facts.providerOf(it, key) }
    }

    fun providerOf(relation: ScopeRelation): ExternalRelationFacts? =
        relation.kind.takeIf { it in PROVIDED }?.let { kind -> relation.source?.let { facts.providerOf(kind, it) } }

    /**
     * Whether [column], at [offset], is visible directly through the slot, table or call of a
     * provider the platform does not resolve itself; not when a CTE or derived table passes it on,
     * where the platform resolves the inner reference.
     */
    fun manages(column: ScopeColumn, offset: Int, file: PsiFile? = null): Boolean {
        if (column.origin == ColumnOrigin.COMPUTED) return false
        val kind = relationKind(column, offset, file)?.takeIf { it in PROVIDED } ?: return false
        return column.source?.relation?.let { facts.providerOf(kind, it) }?.platformResolves == false
    }

    fun manages(relation: ScopeRelation): Boolean = providerOf(relation)?.platformResolves == false

    fun labels(column: ScopeColumn, offset: Int, file: PsiFile? = null, site: RelationScope? = null): RelationColumnLabels =
        providerOf(column, offset, file, site)?.labels ?: profile.labels

    companion object {
        private val PROVIDED = listOf(RelationKind.SLOT, RelationKind.CALL, RelationKind.TABLE)

        /**
         * The analysis of [file] for [profile], reused while the document revision and every
         * provider's revision stay; null for another language or while a provider is pending.
         * Providers are asked about the original of a completion copy.
         */
        fun of(file: PsiFile, profile: RelationScopeProfile): RelationAnalysis? {
            if (file.language != profile.language) return null
            // A completion copy has no document; its own stamp changes with its text.
            val stamp = PsiDocumentManager.getInstance(file.project).getDocument(file)?.modificationStamp ?: file.modificationStamp
            val results = profile.providers.mapNotNull { provider -> provider.facts(file.originalFile)?.let { provider to it } }
            if (results.any { it.second is RelationFactsResult.Pending }) return null
            val available = results.map { (provider, result) -> provider to (result as RelationFactsResult.Available) }
            val revision = Revision(stamp, profile.dialect, available.map { it.first to it.second.revision })
            val cache = file.getUserData(CACHE)
                ?: (file as? UserDataHolderEx)?.putUserDataIfAbsent(CACHE, ConcurrentHashMap())
                ?: ConcurrentHashMap<RelationScopeProfile, Cached>().also { file.putUserData(CACHE, it) }
            cache[profile]?.takeIf { it.revision == revision }?.let { return it.analysis }
            val source = PipeText.of(file)
            val facts = MergedFacts.of(available.map { it.first to it.second.facts })
            val statements = SqlScriptSnapshot.parse(source.text, profile.dialect, ProgressManager::checkCanceled).statements
                .filter { it.hasSql && it.end > it.start }
                .map { statement ->
                    val range = statement.start until statement.end
                    range to RelationScope.analyze(source.text, profile.dialect, facts.merged, range, ProgressManager::checkCanceled)
                }
            return RelationAnalysis(profile, source, facts, statements).also { cache[profile] = Cached(revision, it) }
        }

        /** Per file, each profile's last analysis. */
        private val CACHE = Key.create<ConcurrentHashMap<RelationScopeProfile, Cached>>("brikk.sql.relation.analysis")
    }

    /** What an analysis depends on: the document revision fixes the text; providers count by instance. */
    private data class Revision(val stamp: Long, val dialect: String, val providers: List<Pair<ExternalRelationFacts, Long>>)

    private class Cached(val revision: Revision, val analysis: RelationAnalysis)
}

/** The facts of several providers merged in order, remembering which provider supplied each key of each kind. */
internal class MergedFacts(val merged: RelationFacts, private val owners: Map<Pair<RelationKind, String>, ExternalRelationFacts>) {
    fun providerOf(kind: RelationKind, key: String): ExternalRelationFacts? = owners[kind to key]

    companion object {
        fun of(facts: List<Pair<ExternalRelationFacts, RelationFacts>>): MergedFacts {
            val slots = LinkedHashMap<String, RelationFact>()
            val tables = LinkedHashMap<String, RelationFact>()
            val calls = LinkedHashMap<String, RelationFact>()
            val owners = HashMap<Pair<RelationKind, String>, ExternalRelationFacts>()
            for ((provider, one) in facts) {
                for ((kind, map, entries) in listOf(
                    Triple(RelationKind.SLOT, slots, one.slots),
                    Triple(RelationKind.TABLE, tables, one.tables),
                    Triple(RelationKind.CALL, calls, one.calls),
                )) {
                    for ((name, fact) in entries) if (map.putIfAbsent(name, fact) == null) owners[kind to name] = provider
                }
            }
            // An unlisted table is reported only when every provider that speaks about tables (lists any, or
            // declares its catalog closed) lists all of its own.
            val catalogs = facts.map { it.second }.filter { it.tables.isNotEmpty() || it.catalog == CatalogKnowledge.CLOSED }.map { it.catalog }
            val catalog = if (catalogs.isNotEmpty() && catalogs.all { it == CatalogKnowledge.CLOSED }) CatalogKnowledge.CLOSED else CatalogKnowledge.OPEN
            return MergedFacts(RelationFacts(slots, tables, calls, catalog), owners)
        }
    }
}
