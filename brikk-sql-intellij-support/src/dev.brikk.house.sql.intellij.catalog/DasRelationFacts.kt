package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.model.DasObject
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
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

/**
 * The tables and views of the data source [resolver] finds for a file, as physical relation facts:
 * keyed by their dotted path (catalog, schema, name, as far as the model has them) with every name
 * as stored, so core folds an unquoted SQL name the way the dialect does (PostgreSQL's lower case),
 * and columns with their SQL type and nullability. The platform resolves these tables itself, so
 * they serve the analysis only (completion, PIPE stages, diagnostics). The catalog stays open: the
 * model may lack tables, and a table without columns in it (never introspected to that level)
 * proves no column absent. The search path is the file's console's current namespace, so an
 * unqualified name several schemas hold means the current schema's table, and is unknown without a
 * console namespace. Facts are copies, rebuilt when the data source's model changes; a file
 * without a data source has none, and each data source and model state has its own revision.
 */
public class DasRelationFacts(private val resolver: DialectDataSourceResolver) : ExternalRelationFacts {
    private val built = ConcurrentHashMap<String, Built>()
    private val revisions = AtomicLong()

    /** One data source's facts at its model's [modification] count, numbered by search path among all builds. */
    private class Built(val modification: Long, val facts: RelationFacts) {
        val revisions = ConcurrentHashMap<List<String>, Long>()
    }

    override val platformResolves: Boolean get() = true

    override fun facts(file: PsiFile): RelationFactsResult? = recoverEditorOperation {
        val dataSource = dataSource(file) ?: return@recoverEditorOperation null
        val modification = dataSource.modificationTracker.modificationCount
        val current = built[dataSource.uniqueId]?.takeIf { it.modification == modification }
            ?: Built(modification, copy(dataSource)).also { fresh ->
                built.keys.retainAll(DbPsiFacade.getInstance(file.project).dataSources.mapTo(HashSet()) { it.uniqueId })
                built[dataSource.uniqueId] = fresh
            }
        // The console's current namespace decides which of same-named tables an unqualified name means.
        val searchPath = resolver.consoleNamespace(file).ifEmpty { null }?.let { listOf(catalogPath(it)) }.orEmpty()
        val revision = current.revisions.computeIfAbsent(searchPath) { revisions.incrementAndGet() }
        RelationFactsResult.Available(current.facts.copy(searchPath = searchPath), revision)
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
            tables.putIfAbsent(key, dasRelationFact(table, key))
        }
        return RelationFacts(tables = tables, catalog = CatalogKnowledge.OPEN)
    }
}

/** Separates a column's name from its table's path in a provenance id. */
private const val COLUMN = "#"

/**
 * [table] as a physical relation fact identified by [key]: its columns with their SQL type and nullability, all
 * of them; a table without columns (never introspected to that level) is unknown, proving no column absent.
 */
internal fun dasRelationFact(table: DasTable, key: String): RelationFact {
    val columns = DasUtil.getColumns(table).map { column ->
        ColumnFact(
            name = column.name,
            sqlType = column.dasType.specification.takeIf { it.isNotBlank() },
            nullability = if (column.isNotNull) Nullability.NON_NULL else Nullability.NULLABLE,
            origin = ColumnOrigin.PHYSICAL_TABLE,
            provenance = key + COLUMN + column.name,
        )
    }.toList()
    return RelationFact(columns, if (columns.isEmpty()) Knowledge.UNKNOWN else Knowledge.FULL, provenance = key)
}

/** [table]'s entire namespace path, without turning literal dotted names into extra components. */
internal fun dasTablePath(table: DasTable): String =
    catalogPath(generateSequence(table.dasParent) { it.dasParent }
        .takeWhile { it.kind == ObjectKind.DATABASE || it.kind == ObjectKind.SCHEMA }
        .map { it.name }.toList().asReversed() + table.name)

private fun catalogPath(parts: List<String>): String = parts.filter { it.isNotBlank() }.joinToString(".") {
    if ('.' in it || '"' in it) "\"${it.replace("\"", "\"\"")}\"" else it
}
