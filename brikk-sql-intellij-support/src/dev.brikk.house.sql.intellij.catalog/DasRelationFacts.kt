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
import org.jetbrains.annotations.ApiStatus

/**
 * The tables and views of the data source [resolver] finds for a file, as physical relation facts:
 * keyed by their dotted path (catalog, schema, name, as far as the model has them) with every name
 * as stored, so core folds an unquoted SQL name the way the dialect does (PostgreSQL's lower case),
 * and columns with their SQL type and nullability. The platform resolves these tables itself, so
 * they serve the analysis only (completion, PIPE stages, diagnostics). The catalog stays open: the
 * model may lack tables. Facts are copies, rebuilt when the data source's model changes; a file
 * without a data source has none.
 */
@ApiStatus.Experimental
public class DasRelationFacts(private val resolver: DialectDataSourceResolver) : ExternalRelationFacts {
    private val built = ConcurrentHashMap<String, Pair<Long, RelationFacts>>()

    override val platformResolves: Boolean get() = true

    override fun facts(file: PsiFile): RelationFactsResult? = recoverEditorOperation {
        val dataSource = dataSource(file) ?: return@recoverEditorOperation null
        val revision = dataSource.modificationTracker.modificationCount
        val facts = built[dataSource.uniqueId]?.takeIf { it.first == revision }?.second
            ?: copy(dataSource).also { built[dataSource.uniqueId] = revision to it }
        RelationFactsResult.Available(facts, revision)
    }.getOrNull()

    override fun navigationTarget(file: PsiFile, provenance: String): PsiElement? = recoverEditorOperation {
        val dataSource = dataSource(file) ?: return@recoverEditorOperation null
        val table = DasUtil.getTables(dataSource).firstOrNull { provenance == path(it) || provenance.startsWith(path(it) + COLUMN) }
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
            val key = path(table)
            val columns = DasUtil.getColumns(table).map { column ->
                ColumnFact(
                    name = column.name,
                    sqlType = column.dasType.specification.takeIf { it.isNotBlank() },
                    nullability = if (column.isNotNull) Nullability.NON_NULL else Nullability.NULLABLE,
                    origin = ColumnOrigin.PHYSICAL_TABLE,
                    provenance = key + COLUMN + column.name,
                )
            }.toList()
            tables.putIfAbsent(key, RelationFact(columns, Knowledge.FULL, provenance = key))
        }
        return RelationFacts(tables = tables, catalog = CatalogKnowledge.OPEN)
    }

    private fun path(table: DasTable): String =
        listOf(DasUtil.getCatalog(table), DasUtil.getSchema(table), table.name).filter { it.isNotBlank() }.joinToString(".")

    private companion object {
        /** Separates a column's name from its table's path in a provenance id. */
        const val COLUMN = "#"
    }
}
