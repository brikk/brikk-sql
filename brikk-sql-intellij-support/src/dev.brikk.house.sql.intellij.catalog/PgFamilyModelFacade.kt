package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.datagrid.CoreGrid
import com.intellij.database.datagrid.GridColumn
import com.intellij.database.datagrid.GridRow
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.dialects.postgres.model.PgMetaModel
import com.intellij.database.dialects.postgres.model.PgModelHelper
import com.intellij.database.dialects.postgresgreenplumbase.model.PgGPlumBaseModelHelper
import com.intellij.database.dialects.postgresgreenplumbase.model.PgGPlumBaseSequence
import com.intellij.database.model.DataType
import com.intellij.database.model.ElementDelta
import com.intellij.database.model.ModelFacade
import com.intellij.database.model.ModelHelper
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModElement
import com.intellij.database.model.basic.BasicTable
import com.intellij.database.model.basic.BasicTrigger
import com.intellij.database.model.basic.BasicTypedElement
import com.intellij.database.model.meta.BasicMetaField
import com.intellij.database.model.meta.BasicMetaModel
import com.intellij.database.model.meta.BasicMetaObject
import com.intellij.database.util.Version

/**
 * The PostgreSQL model for a dbms whose dialect is PostgreSQL-based and whose `extensionFallback` is
 * `POSTGRES`. The model facade, the dialect's metadata family and the introspector must agree on one
 * model family, or attaching a data source fails with a `ClassCastException`. The PostgreSQL model
 * has a database level, so multi-catalog engines can show their catalogs as databases.
 *
 * With [databasesAsCatalogs], the database tree and the schemas pane call that level "catalog".
 * Register a subclass as `<modelFacade dbms="..." implementationClass="..."/>`; the platform passes
 * the dbms to its one-argument constructor.
 */
public abstract class PgFamilyModelFacade(dbms: Dbms, databasesAsCatalogs: Boolean = false) : ModelFacade(dbms) {
    private val helper: ModelHelper = if (databasesAsCatalogs) PgFamilyCatalogModelHelper else PgModelHelper

    final override fun getMetaModel(): BasicMetaModel<*> = PgMetaModel.MODEL

    final override fun getModelHelper(): ModelHelper = helper
}

/**
 * [PgModelHelper] with the DATABASE kind named "catalog", the per-dbms naming hook Cassandra uses for
 * "keyspace". `PgModelHelper` is final, so its overrides are forwarded to it.
 */
internal object PgFamilyCatalogModelHelper : PgGPlumBaseModelHelper() {
    override fun getCustomName(kind: ObjectKind, plural: Boolean): String? = when (kind) {
        ObjectKind.DATABASE -> if (plural) "catalogs" else "catalog"
        else -> super.getCustomName(kind, plural)
    }

    override val sequenceTypeSince: Version get() = PgModelHelper.sequenceTypeSince

    override fun getSyntheticColumnNameByPosition(position: Short): String? = PgModelHelper.getSyntheticColumnNameByPosition(position)

    override fun nodeTriggerSuffix(trigger: BasicTrigger): String? = PgModelHelper.nodeTriggerSuffix(trigger)

    override fun nodeTableSuffix(table: BasicTable): String? = PgModelHelper.nodeTableSuffix(table)

    override fun gridColumnHasDefaultValue(element: BasicElement): Boolean = PgModelHelper.gridColumnHasDefaultValue(element)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getJdbcType(element: BasicTypedElement): Int = PgModelHelper.getJdbcType(element)

    override fun ddlPreviewProcessCorrespondence(deltas: MutableCollection<ElementDelta<*>>, delta: ElementDelta<BasicElement>): Unit =
        PgModelHelper.ddlPreviewProcessCorrespondence(deltas, delta)

    override fun docAppendInfo(element: BasicElement, sb: StringBuilder): Unit = PgModelHelper.docAppendInfo(element, sb)

    override fun getDataTypeForGridCompletion(grid: CoreGrid<GridRow, GridColumn>, column: ModelIndex<GridColumn>): DataType? =
        PgModelHelper.getDataTypeForGridCompletion(grid, column)

    override fun hasDefaultType(sequence: PgGPlumBaseSequence): Boolean = PgModelHelper.hasDefaultType(sequence)

    override fun fillPropertiesWithExamples(
        e: BasicModElement,
        variant: ObjectKindVariant,
        vars: MutableMap<String, String>,
        modifyParent: Boolean,
    ): Unit = PgModelHelper.fillPropertiesWithExamples(e, variant, vars, modifyParent)

    override fun <E : BasicElement> reorderProperties(meta: BasicMetaObject<E>, fields: MutableList<BasicMetaField<E>>): Unit =
        PgModelHelper.reorderProperties(meta, fields)
}
