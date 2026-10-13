package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dialects.AbstractDefinitionProvider
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.PairConsumer
import dev.brikk.house.sql.shape.DefinedObject
import dev.brikk.house.sql.shape.DefinitionQuery
import dev.brikk.house.sql.shape.definitionQuery
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.annotations.ApiStatus

/**
 * Object definitions (the source the IDE shows for a table, view, ...) read from the server's catalog
 * with core's [definitionQuery] for the core [dialect]: Doris and Trino `SHOW CREATE`, DuckDB's
 * `duckdb_*()` tables. [kinds] says which model kinds are which defined objects; a path is the object's
 * ancestors of the namespace [levels], top first, then its name. The queries only read the catalog, over
 * the connection the platform hands over for loading sources, never a console's. Register a no-arg
 * subclass as `<definitionProvider dbms="X">`.
 */
@ApiStatus.Experimental
public abstract class ShowCreateDefinitionProvider(
    private val dialect: String,
    private val kinds: Map<ObjectKind, DefinedObject>,
    private val levels: List<ObjectKind>,
) : AbstractDefinitionProvider() {
    override fun isSupported(`object`: DasObject): Boolean = query(`object`) != null

    /** The catalog read for [obj], or null when the dialect has none for it. */
    public fun query(obj: DasObject): DefinitionQuery? {
        val kind = kinds[obj.kind] ?: return null
        val path = generateSequence(obj.dasParent) { it.dasParent }.filter { it.kind in levels }.map { it.name }.toList().asReversed() + obj.name
        return definitionQuery(dialect, kind, path)
    }

    override fun fetchSources(objects: Iterable<DasObject>, connection: DatabaseConnectionCore, consumer: PairConsumer<DasObject, Any>) {
        for (obj in objects) {
            ProgressManager.checkCanceled()
            val query = query(obj) ?: continue
            val definition = try {
                query.definition(connection.queryRows(query.sql, MAX_ROWS)).orEmpty()
            } catch (e: Exception) {
                if (e is ControlFlowException || e is CancellationException) throw e
                consumer.consume(obj, e)
                continue
            }
            consumer.consume(obj, definition)
        }
    }

    private companion object {
        /** A macro has one row per overload; nothing has more. */
        const val MAX_ROWS = 1000
    }
}
