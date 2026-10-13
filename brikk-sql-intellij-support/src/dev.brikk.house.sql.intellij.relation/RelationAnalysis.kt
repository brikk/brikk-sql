package dev.brikk.house.sql.intellij.relation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
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

    /** The provider of [column]'s fact; null for a computed column. */
    fun providerOf(column: ScopeColumn): ExternalRelationFacts? = column.source?.let { facts.providerOf(it.kind, it.relation) }

    fun providerOf(relation: ScopeRelation): ExternalRelationFacts? =
        relation.kind.takeIf { it in PROVIDED }?.let { kind -> relation.source?.let { facts.providerOf(kind, it) } }

    /**
     * Whether [column] is the analysis's to resolve: a fact of a provider the platform does not
     * resolve itself, seen directly through its slot, table or call; not when a CTE or derived table
     * passes it on, where the platform resolves the inner reference.
     */
    fun manages(column: ScopeColumn, offset: Int): Boolean =
        column.origin != ColumnOrigin.COMPUTED && column.relationKind in PROVIDED && providerOf(column)?.platformResolves == false

    fun manages(relation: ScopeRelation): Boolean = providerOf(relation)?.platformResolves == false

    fun labels(column: ScopeColumn): RelationColumnLabels = providerOf(column)?.labels ?: profile.labels

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
