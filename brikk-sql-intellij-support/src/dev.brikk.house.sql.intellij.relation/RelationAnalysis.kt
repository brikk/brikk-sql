package dev.brikk.house.sql.intellij.relation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnOrigin
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
    val references: List<ScopeReference> get() = statements.flatMap { it.second.references }

    /** The innermost reference covering [offset] of the text, or the one ending there. */
    fun referenceAt(offset: Int): ScopeReference? =
        statements.firstOrNull { offset in it.first.first..it.first.last + 1 }?.second?.referenceAt(offset)

    /** The statement range holding [offset], its end included. */
    fun statementAt(offset: Int): IntRange? = statements.firstOrNull { offset in it.first.first..it.first.last + 1 }?.first

    /** The provider of [column]'s fact, or null for a computed column. */
    fun providerOf(column: ScopeColumn): ExternalRelationFacts? = column.source?.relation?.let(facts::providerOf)

    fun providerOf(relation: ScopeRelation): ExternalRelationFacts? = when (relation.kind) {
        RelationKind.SLOT, RelationKind.TABLE, RelationKind.CALL -> relation.source?.let(facts::providerOf)
        else -> null
    }

    /** Whether [column] comes from a provider whose relations the platform does not resolve itself. */
    fun manages(column: ScopeColumn): Boolean =
        column.origin != ColumnOrigin.COMPUTED && providerOf(column)?.platformResolves == false

    fun manages(relation: ScopeRelation): Boolean = providerOf(relation)?.platformResolves == false

    fun labels(column: ScopeColumn): RelationColumnLabels = providerOf(column)?.labels ?: profile.labels

    companion object {
        /**
         * The analysis of [file] for [profile], reused while the document revision, the text and every
         * provider's revision stay; null for another language or while a provider is pending.
         */
        fun of(file: PsiFile, profile: RelationScopeProfile): RelationAnalysis? {
            if (file.language != profile.language) return null
            val stamp = PsiDocumentManager.getInstance(file.project).getDocument(file)?.modificationStamp ?: return null
            val results = profile.providers.mapNotNull { provider -> provider.facts(file)?.let { provider to it } }
            if (results.any { it.second is RelationFactsResult.Pending }) return null
            val available = results.map { (provider, result) -> provider to (result as RelationFactsResult.Available) }
            val source = PipeText.of(file)
            val key = Revision(stamp, source.text, profile.dialect, available.map { it.first to it.second.revision })
            val cacheKey = KEYS.computeIfAbsent(profile.language.id) { Key.create("brikk.sql.relation.analysis.$it") }
            file.getUserData(cacheKey)?.takeIf { it.revision == key && it.analysis.profile === profile }?.let { return it.analysis }
            val facts = MergedFacts.of(available.map { it.first to it.second.facts })
            val statements = SqlScriptSnapshot.parse(source.text, profile.dialect, ProgressManager::checkCanceled).statements
                .filter { it.hasSql && it.end > it.start }
                .map { statement ->
                    val range = statement.start until statement.end
                    range to RelationScope.analyze(source.text, profile.dialect, facts.merged, range, ProgressManager::checkCanceled)
                }
            return RelationAnalysis(profile, source, facts, statements).also { file.putUserData(cacheKey, Cached(key, it)) }
        }

        private val KEYS = ConcurrentHashMap<String, Key<Cached>>()
    }

    /** What an analysis depends on; providers count by instance. */
    private data class Revision(val stamp: Long, val text: String, val dialect: String, val providers: List<Pair<ExternalRelationFacts, Long>>)

    private class Cached(val revision: Revision, val analysis: RelationAnalysis)
}

/** The facts of several providers merged in order, remembering which provider supplied each key. */
internal class MergedFacts(val merged: RelationFacts, private val owners: Map<String, ExternalRelationFacts>) {
    fun providerOf(key: String): ExternalRelationFacts? = owners[key]

    companion object {
        fun of(facts: List<Pair<ExternalRelationFacts, RelationFacts>>): MergedFacts {
            val slots = LinkedHashMap<String, RelationFact>()
            val tables = LinkedHashMap<String, RelationFact>()
            val calls = LinkedHashMap<String, RelationFact>()
            val owners = HashMap<String, ExternalRelationFacts>()
            for ((provider, one) in facts) {
                for ((map, entries) in listOf(slots to one.slots, tables to one.tables, calls to one.calls)) {
                    for ((name, fact) in entries) if (map.putIfAbsent(name, fact) == null) owners.putIfAbsent(name, provider)
                }
            }
            // An unlisted table is reported only when every provider of tables lists all of its own.
            val catalogs = facts.map { it.second }.filter { it.tables.isNotEmpty() }.map { it.catalog }
            val catalog = if (catalogs.isNotEmpty() && catalogs.all { it == CatalogKnowledge.CLOSED }) CatalogKnowledge.CLOSED else CatalogKnowledge.OPEN
            return MergedFacts(RelationFacts(slots, tables, calls, catalog), owners)
        }
    }
}
