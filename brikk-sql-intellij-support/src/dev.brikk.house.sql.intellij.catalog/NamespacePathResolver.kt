package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import org.jetbrains.annotations.ApiStatus

/** A model object addressed by its absolute path, top level first (for example catalog, schema). */
@ApiStatus.Experimental
public data class NamespaceTarget(val path: List<String>, val node: DasObject)

/**
 * Resolves a dotted path the user typed against a data source's introspected model. [levels] are the
 * dbms's namespace kinds, top level first: `[DATABASE, SCHEMA]` where catalogs are databases, or
 * `[SCHEMA]`. A path is tried as absolute first; with [usesCurrentNamespace], then relative to the
 * console's current namespace, under its first one, two, ... names. Paths may address objects below
 * the namespaces, up to [maxDepth] names in all.
 *
 * Names match exactly, else ignoring case. A path that does not resolve (still being typed, or a
 * typo) gives null.
 */
@ApiStatus.Experimental
public class NamespacePathResolver(
    public val levels: List<ObjectKind>,
    private val usesCurrentNamespace: Boolean,
    private val maxDepth: Int = levels.size,
    private val childrenOf: (DasObject) -> List<DasObject> = { loadedChildren(it, levels) },
) {
    init {
        require(levels.isNotEmpty()) { "A dbms has at least one namespace level" }
        require(maxDepth >= 1) { "maxDepth must be positive" }
    }

    /** [roots] are the model's top-level objects, of kind `levels[0]`. */
    public fun resolve(roots: List<DasObject>, path: List<String>, currentNamespace: List<String> = emptyList()): NamespaceTarget? {
        if (path.isEmpty() || path.size > maxDepth) return null
        val prefixes = if (usesCurrentNamespace) 0..minOf(currentNamespace.size, maxDepth - path.size) else 0..0
        return prefixes.asSequence().map { currentNamespace.take(it) + path }.firstNotNullOfOrNull { absolute ->
            val top = roots.named(absolute.first()) ?: return@firstNotNullOfOrNull null
            absolute.drop(1).fold<String, DasObject?>(top) { node, name -> node?.let(childrenOf)?.named(name) }
                ?.let { NamespaceTarget(absolute, it) }
        }
    }

    /** Whether [node] has no loaded children: it is listed but was never introspected. */
    public fun isChildless(node: DasObject): Boolean = childrenOf(node).isEmpty()

    private fun List<DasObject>.named(name: String): DasObject? =
        firstOrNull { it.name == name } ?: firstOrNull { it.name.equals(name, ignoreCase = true) }

    public companion object {
        /**
         * Children of every kind: the kind-specific views of the namespace levels and tables, with the
         * all-kinds view, which can skip a subtree in two-level models.
         */
        public fun loadedChildren(node: DasObject, levels: List<ObjectKind>): List<DasObject> =
            ((levels + ObjectKind.TABLE).flatMap { node.getDasChildren(it).toList() } + node.getDasChildren(null).toList()).distinct()
    }
}
