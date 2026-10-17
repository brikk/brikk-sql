package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternNode
import com.intellij.database.util.TreePatternUtils
import com.intellij.sql.dialects.SqlImportUtil
import org.jetbrains.annotations.ApiStatus

/**
 * SQL editor imports for dialects whose own imports reach only schemas, such as the MySQL family,
 * over a model whose top level is catalogs (`DATABASE`): without a catalog-level import, the head of
 * `catalog.schema.table` never resolves. A dialect overrides `getBaseImports(dataSource, names)` with
 * `CatalogImports.allCatalogs(super.getBaseImports(dataSource, names), names)`. PostgreSQL-family
 * dialects already resolve catalog heads and need none of this.
 */
@ApiStatus.Experimental
public object CatalogImports {
    /**
     * [base] plus every catalog of the data sources [dataSourceNames] as a qualifier head. Catalogs'
     * schemas are not imported, so unqualified names keep resolving through [base] alone. Without data
     * source names (a file with no data source) [base] is returned unchanged.
     */
    public fun allCatalogs(base: TreePattern, dataSourceNames: Array<out ObjectName?>?): TreePattern {
        val names = dataSourceNames.orEmpty().filterNotNull().toTypedArray()
        // An empty data source list would make an empty positive naming, which the platform rejects.
        if (names.isEmpty()) return base
        val anyCatalog = TreePatternNode(TreePatternNode.NegativeNaming.WILDCARD, TreePatternNode.NO_GROUPS)
        val catalogs = TreePatternNode.Group(ObjectKind.DATABASE, arrayOf(anyCatalog))
        return TreePatternUtils.union(base, TreePattern(SqlImportUtil.createDataSources(names, catalogs)))
    }
}
