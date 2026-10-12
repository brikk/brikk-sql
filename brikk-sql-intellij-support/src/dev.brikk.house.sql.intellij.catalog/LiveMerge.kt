package dev.brikk.house.sql.intellij.catalog

import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.KeywordCatalog
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.metadata.presentation
import org.jetbrains.annotations.ApiStatus

/**
 * How a live inventory combines with the bundled functions for completion. Without a harvest every
 * policy offers the bundled functions unchanged. Session facts are always offered.
 */
@ApiStatus.Experimental
public enum class LiveMergePolicy {
    /**
     * The live functions replace the bundled registry: a function with live signatures is presented
     * with them, a names-only one with the bundled def of that name and kind if there is one. Bundled
     * entries no registry def backs (grammar forms) and extension functions the server does not list
     * (not loaded yet) stay.
     */
    REPLACE,

    /**
     * Bundled registry functions the server does not list are dropped; listed names the bundle lacks
     * are server-only functions; platform builtin items neither the bundle nor the server knows are hidden.
     */
    FILTER_AND_EXTEND,

    /** The bundled functions stay; listed names the bundle lacks are server-only functions. */
    EXTEND,
}

/** What completion offers from one selection: the profile hooks' answers. */
@ApiStatus.Experimental
public class LiveCompletion(
    public val selection: LiveCatalogSelection,
    public val snapshot: MetadataSnapshot,
    public val functions: List<FunctionPresentation>,
    public val serverOnlyFunctions: List<FunctionPresentation>,
    public val keywords: KeywordCatalog?,
    public val builtinFunctionFilter: ((String) -> Boolean)?,
)

/**
 * Combines [selection] with [baseline], the functions offered without a harvest (usually
 * `snapshot.completableFunctions()`), under this policy. Names compare by the snapshot's case policy.
 */
@ApiStatus.Experimental
public fun LiveMergePolicy.merge(selection: LiveCatalogSelection, snapshot: MetadataSnapshot, baseline: List<FunctionPresentation>): LiveCompletion {
    val catalog = snapshot.functions
    fun key(name: String) = if (catalog.caseSensitive) name else name.uppercase()
    val session = selection.session.functions.map { it.def.presentation() }
    val inventory = selection.inventory
        ?: return LiveCompletion(selection, snapshot, distinct(baseline + session, ::key), emptyList(), snapshot.keywords, null)
    val keywords = inventory.keywords?.let(::KeywordCatalog) ?: snapshot.keywords
    val listed = inventory.functions.map { it.def }
    val listedNames = listed.mapTo(HashSet()) { key(it.name) }
    val baselineNames = baseline.mapTo(HashSet()) { key(it.name) }
    fun registryBacked(entry: FunctionPresentation) = catalog.getAll(entry.name).any { it.overloads.isNotEmpty() }
    val serverOnly by lazy {
        listed.filter { key(it.name) !in baselineNames && snapshot.tableFunction(it.name)?.queryable != false }
            .map { it.presentation() }
    }
    return when (this) {
        LiveMergePolicy.REPLACE -> {
            val forms = snapshot.grammarForms.orEmpty().associateBy { key(it.name) to it.kind }
            val live = listed.map { def ->
                val presented = if (inventory.signatures) def.presentation()
                else catalog.getAll(def.name).firstOrNull { it.kind == def.kind }?.presentation(def.name) ?: def.presentation()
                forms[key(def.name) to def.kind]?.let { presented.copy(syntaxes = it.syntaxes, template = it.template) } ?: presented
            }
            val kept = baseline.filter { entry ->
                key(entry.name) !in listedNames && (entry.extension != null || !registryBacked(entry))
            }
            LiveCompletion(selection, snapshot, distinct(live + kept + session, ::key), emptyList(), keywords, null)
        }
        LiveMergePolicy.FILTER_AND_EXTEND -> {
            val kept = baseline.filter { catalog.getAll(it.name).isEmpty() || key(it.name) in listedNames }
            val filter = { name: String -> catalog.isKnown(name) || key(name) in listedNames }
            LiveCompletion(selection, snapshot, distinct(kept + session, ::key), serverOnly, keywords, filter)
        }
        LiveMergePolicy.EXTEND -> LiveCompletion(selection, snapshot, distinct(baseline + session, ::key), serverOnly, keywords, null)
    }
}

/** One entry per name and kind, the first one winning. */
private fun distinct(entries: List<FunctionPresentation>, key: (String) -> String): List<FunctionPresentation> =
    entries.distinctBy { key(it.name) to it.kind }

