package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.model.DasObject
import com.intellij.database.model.DasTable
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.intellij.relation.ExternalRelationFacts
import dev.brikk.house.sql.intellij.relation.RelationFactsResult
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.annotations.ApiStatus

/**
 * The tables and views of the data source [resolver] finds for a file, as physical relation facts:
 * keyed by their dotted path (catalog, schema, name, as far as the model has them) with every name
 * as stored, so core folds an unquoted SQL name the way the dialect does (PostgreSQL's lower case),
 * and columns with their SQL type and nullability. The platform resolves these tables itself, so
 * they serve the analysis only (completion, PIPE stages, diagnostics). The catalog stays open: the
 * model may lack tables, and a table without columns in it (never introspected to that level)
 * proves no column absent. Facts are copies, rebuilt when the data source's model changes; a file
 * without a data source has none, and each data source and model state has its own revision.
 */
@ApiStatus.Experimental
public class DasRelationFacts(private val resolver: DialectDataSourceResolver) : ExternalRelationFacts {
    private val built = ConcurrentHashMap<String, Built>()
    private val revisions = AtomicLong()

    /** One data source's facts at its model's [modification] count, numbered [revision] among all builds. */
    private class Built(val modification: Long, val revision: Long, val facts: RelationFacts)

    override val platformResolves: Boolean get() = true

    override fun facts(file: PsiFile): RelationFactsResult? = recoverEditorOperation {
        val dataSource = dataSource(file) ?: return@recoverEditorOperation null
        val modification = dataSource.modificationTracker.modificationCount
        val current = built[dataSource.uniqueId]?.takeIf { it.modification == modification }
            ?: Built(modification, revisions.incrementAndGet(), copy(dataSource)).also { fresh ->
                built.keys.retainAll(DbPsiFacade.getInstance(file.project).dataSources.mapTo(HashSet()) { it.uniqueId })
                built[dataSource.uniqueId] = fresh
            }
        RelationFactsResult.Available(current.facts, current.revision)
    }.getOrNull()

    override fun navigationTarget(file: PsiFile, provenance: String): PsiElement? = recoverEditorOperation {
        val dataSource = dataSource(file) ?: return@recoverEditorOperation null
        val table = DasUtil.getTables(dataSource).firstOrNull { provenance == dasTablePath(it) || provenance.startsWith(dasTablePath(it) + COLUMN) }
            ?: return@recoverEditorOperation null
        val target: DasObject = provenance.substringAfter(COLUMN, "").takeIf { it.isNotEmpty() }
            ?.let { name -> DasUtil.getColumns(table).firstOrNull { it.name == name } } ?: table
        dataSource.findElement(target)
    }.getOrNull()

    private fun dataSource(file: PsiFile): DbDataSource? =
        resolver.resolve(file).dataSource?.let { DbPsiFacade.getInstance(file.project).findDataSource(it.uniqueId) }

    private fun copy(dataSource: DbDataSource): RelationFacts {
        val tables = LinkedHashMap<String, RelationFact>()
        for (table in DasUtil.getTables(dataSource)) {
            val key = dasTablePath(table)
            val columns = DasUtil.getColumns(table).map { column ->
                ColumnFact(
                    name = column.name,
                    sqlType = column.dasType.specification.takeIf { it.isNotBlank() },
                    nullability = if (column.isNotNull) Nullability.NON_NULL else Nullability.NULLABLE,
                    origin = ColumnOrigin.PHYSICAL_TABLE,
                    provenance = key + COLUMN + column.name,
                )
            }.toList()
            tables.putIfAbsent(key, RelationFact(columns, if (columns.isEmpty()) Knowledge.UNKNOWN else Knowledge.FULL, provenance = key))
        }
        return RelationFacts(tables = tables, catalog = CatalogKnowledge.OPEN)
    }

    private companion object {
        /** Separates a column's name from its table's path in a provenance id. */
        const val COLUMN = "#"
    }
}

/** [table]'s key in relation facts: its catalog, schema and name as stored, as far as the model has them. */
internal fun dasTablePath(table: DasTable): String =
    listOf(DasUtil.getCatalog(table), DasUtil.getSchema(table), table.name).filter { it.isNotBlank() }.joinToString(".")
