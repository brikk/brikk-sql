package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.model.ObjectKind
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * How a console switches and reads back its current namespace, for a dbms whose namespaces are
 * catalogs (`DATABASE`) holding schemas (`SCHEMA`). A plugin's `<dialect>` subclass delegates
 * `sqlSetSearchPath` to [switchSql] and `tryToLoadSearchPath` to [readBack]: the platform runs the
 * first when the user picks a namespace, and the second after each execution to re-sync the console.
 */
public interface CatalogSearchPathStrategy {
    /** The statement that makes [path]'s current namespace the session's; null when it cannot be switched to. */
    public fun switchSql(path: SearchPath): String?

    /** The session's current namespace, read over [connection] (outermost first); null when unknown. */
    public fun readBack(connection: DatabaseConnectionCore): SearchPath?
}

/** One way to read a current catalog or schema name. A probe that fails or finds nothing gives null. */
public sealed interface NamespaceProbe {
    /** The first column of the first row of [sql], such as `select database()`. */
    public data class Value(val sql: String) : NamespaceProbe

    /** The column labelled [column] (ignoring case) of the first row of [sql]. */
    public data class Column(val sql: String, val column: String) : NamespaceProbe

    /** The [nameColumn] of the row of [sql] whose [flagColumn] says yes, true or 1, such as Doris `SHOW CATALOGS` `IsCurrent`. */
    public data class FlaggedRow(val sql: String, val nameColumn: String, val flagColumn: String) : NamespaceProbe
}

/**
 * A [CatalogSearchPathStrategy] from statement templates and read-back probes. Templates take
 * `{catalog}` and `{schema}`, each replaced by the name quoted with [quote] (a doubled quote stands for
 * itself): [useSchema] switches to a schema of a catalog, [useBareSchema] to a schema without a catalog
 * and [useCatalog] to a catalog alone; a null template cannot switch to that shape.
 *
 * The read-back tries [catalogProbes] in order until one names a catalog, else uses [defaultCatalog],
 * and likewise [schemaProbes]. Probes with the same SQL run it once. Without a catalog the path is
 * unknown; without a schema it is the catalog alone, unless [schemaRequired].
 *
 * The probes run on the console's own connection after every execution, inside an open transaction
 * too. Where a failed statement aborts the transaction (PostgreSQL and engines like it), list only
 * probes the server supports: a fallback probe that fails there breaks the user's transaction.
 */
public class CatalogSchemaSearchPath(
    private val quote: Char,
    private val useSchema: String,
    private val useBareSchema: String? = null,
    private val useCatalog: String? = null,
    private val catalogProbes: List<NamespaceProbe>,
    private val schemaProbes: List<NamespaceProbe>,
    private val defaultCatalog: String? = null,
    private val schemaRequired: Boolean = false,
) : CatalogSearchPathStrategy {
    private val failedProbes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun switchSql(path: SearchPath): String? {
        val current = path.current ?: return null
        // This strategy represents catalog/schema, not recursive schema ancestry. Never
        // silently switch to a different namespace by dropping an intermediate schema.
        if (current.kind == ObjectKind.SCHEMA && current.findParent(ObjectKind.SCHEMA, true) != null) return null
        return when (current.kind) {
            ObjectKind.SCHEMA -> when (val catalog = current.findParent(ObjectKind.DATABASE, true)) {
                null -> useBareSchema?.fill(catalog = null, schema = current.name)
                else -> useSchema.fill(catalog.name, current.name)
            }
            ObjectKind.DATABASE -> useCatalog?.fill(catalog = current.name, schema = null)
            else -> null
        }
    }

    override fun readBack(connection: DatabaseConnectionCore): SearchPath? {
        val rows = HashMap<String, List<Map<String, String?>>>()
        fun probe(probes: List<NamespaceProbe>) = probes.firstNotNullOfOrNull { read(connection, it, rows) }
        val catalog = probe(catalogProbes) ?: defaultCatalog ?: return null
        val schema = probe(schemaProbes)
        if (schema == null && schemaRequired) return null
        val catalogPath = ObjectPath.create(catalog, ObjectKind.DATABASE)
        return SearchPath.of(schema?.let { catalogPath.append(it, ObjectKind.SCHEMA) } ?: catalogPath)
    }

    /** One pass over the template, so an inserted name is never read as a token. */
    private fun String.fill(catalog: String?, schema: String?): String = TOKEN.replace(this) { token ->
        val name = if (token.value == "{catalog}") catalog else schema
        name?.let(::quoted).orEmpty()
    }

    private fun quoted(name: String): String = "$quote${name.replace("$quote", "$quote$quote")}$quote"

    private fun read(connection: DatabaseConnectionCore, probe: NamespaceProbe, cache: MutableMap<String, List<Map<String, String?>>>): String? {
        val sql = when (probe) {
            is NamespaceProbe.Value -> probe.sql
            is NamespaceProbe.Column -> probe.sql
            is NamespaceProbe.FlaggedRow -> probe.sql
        }
        val rows = cache.getOrPut(sql) { query(connection, sql) }
        val value = when (probe) {
            is NamespaceProbe.Value -> rows.firstOrNull()?.get(FIRST)
            is NamespaceProbe.Column -> rows.firstOrNull()?.get(probe.column.uppercase())
            is NamespaceProbe.FlaggedRow -> rows.firstOrNull { it[probe.flagColumn.uppercase()]?.lowercase() in FLAGS }?.get(probe.nameColumn.uppercase())
        }
        return value?.takeIf(String::isNotBlank)
    }

    /** The rows of [sql] by upper-cased column label (the first column also under [FIRST]); empty when it fails. */
    private fun query(connection: DatabaseConnectionCore, sql: String): List<Map<String, String?>> = try {
        val statement = JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() } ?: error("No statement")
        try {
            val result = JdbcNativeUtil.computeRemote { statement.executeQuery(sql) } ?: error("No result for $sql")
            try {
                val meta = result.metaData
                val labels = (1..meta.columnCount).map { meta.getColumnLabel(it).orEmpty().uppercase() }
                buildList {
                    while (result.next() && size < MAX_ROWS) {
                        add(buildMap {
                            labels.forEachIndexed { index, label -> put(label, result.getString(index + 1)) }
                            put(FIRST, result.getString(1))
                        })
                    }
                }
            } finally {
                JdbcNativeUtil.performSafe { result.close() }
            }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    } catch (e: Exception) {
        if (e is ControlFlowException || e is CancellationException) throw e
        // A probe the server does not support falls through to the next one; it runs after every execution, so say so once.
        if (failedProbes.add(sql)) LOG.info("Search path probe '$sql' failed: ${e.message}")
        emptyList()
    }

    private companion object {
        val LOG = logger<CatalogSchemaSearchPath>()
        const val FIRST = "\u0000first"
        const val MAX_ROWS = 1000
        val FLAGS = setOf("yes", "true", "1")
        val TOKEN = Regex("""\{(?:catalog|schema)}""")
    }
}
