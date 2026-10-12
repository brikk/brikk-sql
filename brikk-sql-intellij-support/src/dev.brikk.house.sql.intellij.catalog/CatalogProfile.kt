package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternNode
import com.intellij.database.util.TreePatternUtils
import com.intellij.database.model.ObjectName
import com.intellij.database.util.Casing
import org.jetbrains.annotations.ApiStatus

/**
 * A dialect's catalog facts for auto-introspection and path completion. The minimum is [dbms],
 * [productLabel], [quoteChars] and [levels]; the rest has defaults.
 */
@ApiStatus.Experimental
public interface CatalogProfile {
    public val dbms: Dbms

    /** The product name in user-visible text, such as `Trino`. */
    public val productLabel: String

    /** Characters that quote identifiers, such as `"` or `` ` ``. */
    public val quoteChars: String

    /** Namespace kinds, top level first: `[DATABASE, SCHEMA]` where catalogs are databases, or `[SCHEMA]`. */
    public val levels: List<ObjectKind>

    /** Whether paths may be relative to the console's current namespace (`USE catalog.schema`). */
    public val usesCurrentNamespace: Boolean get() = false

    /** How many names a path that triggers introspection may have; more than [levels] reaches objects such as tables. */
    public val maxDeepenDepth: Int get() = levels.size

    /** Introspecting what a path names when it is listed but not loaded. Off until a dialect enables it. */
    public val autoIntrospect: Boolean get() = false

    /**
     * Unresolved references into namespaces that are listed but never introspected are not reported
     * ([OutOfScopeReferenceFilter]) and offer to introspect them ([IntrospectNamespaceIntention]). Off
     * until a dialect enables it.
     */
    public val outOfScopeReferences: Boolean get() = false

    /** Which data source serves a file. Override with a stored instance to set the resolver's options. */
    public val dataSources: DialectDataSourceResolver get() = DialectDataSourceResolver(dbms)

    /** The loaded children of a model object. */
    public fun childrenOf(node: DasObject): List<DasObject> = NamespacePathResolver.loadedChildren(node, levels)
}

/** The path resolver for this profile's levels, current-namespace rule, depth and children. */
@get:ApiStatus.Experimental
public val CatalogProfile.pathResolver: NamespacePathResolver
    get() = NamespacePathResolver(levels, usesCurrentNamespace, maxDeepenDepth, ::childrenOf)

/** Introspection scopes, which select what a data source's introspection loads. */
@ApiStatus.Experimental
public object CatalogScopes {
    /**
     * Selects exactly the namespace at [path] (kinds [levels], top first; names beyond the levels are
     * ignored). Selecting a node loads its direct children only, not its subtree, so a refresh of a
     * catalog lists its schemas without loading their tables.
     */
    public fun select(levels: List<ObjectKind>, path: List<String>): TreePattern {
        val names = path.take(levels.size)
        require(names.isNotEmpty()) { "A scope selects at least one namespace" }
        val leaf = TreePatternNode(TreePatternNode.PositiveNaming(ObjectName.plain(names.last())), TreePatternNode.NO_GROUPS)
        val top = names.indices.reversed().drop(1).fold(TreePatternNode.Group(levels[names.lastIndex], arrayOf(leaf))) { group, level ->
            TreePatternNode.Group(levels[level], arrayOf(TreePatternNode(TreePatternNode.PositiveNaming(ObjectName.plain(names[level])), arrayOf(group))))
        }
        return TreePattern(top)
    }

    /** Whether [scope] selects the namespace at [path] (kinds [levels], top first). */
    internal fun selects(scope: TreePattern?, levels: List<ObjectKind>, path: List<String>): Boolean {
        var node = scope?.root ?: return false
        for ((level, name) in path.withIndex()) {
            val kind = levels.getOrNull(level) ?: return false
            node = node.getGroup(kind)?.children?.firstOrNull { it.naming.matches(ObjectName.plain(name), Casing.EXACT) } ?: return false
        }
        return true
    }

    /** [current] with [addition] added, keeping everything [current] selects. */
    public fun widen(current: TreePattern?, addition: TreePattern): TreePattern = current?.let { TreePatternUtils.union(it, addition) } ?: addition
}
