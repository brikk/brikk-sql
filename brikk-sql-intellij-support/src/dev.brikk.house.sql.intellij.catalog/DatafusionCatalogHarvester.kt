package dev.brikk.house.sql.intellij.catalog

import dev.brikk.house.sql.intellij.dialect.SCALAR_STUB_KIND
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.scope.Nullability

/** The required queries are atomic as a harvest: any denied, malformed or interrupted part rejects it. */
public open class DatafusionCatalogHarvester protected constructor(private val product: String) : LiveCatalogHarvester {
    public constructor() : this("datafusion")

    override fun harvest(rows: CatalogRowSource): Harvest? {
        val info = (rows as? CatalogServerInfoSource)?.serverInfo()
        if (info?.product?.contains("spice", true) == true && product != "spice") return null
        if (product == "spice" && info?.product?.contains("spice", true) != true) return null
        fun read(sql: String, width: Int): List<List<String?>> = buildList {
            rows.forEachRow(sql) { row -> add((1..width).map(row::string)) }
        }
        fun version(): String? = read(SQL_VERSION, 1).singleOrNull()?.single()
        fun settings(): Map<String, String?> = read(SQL_SETTINGS, 2).associate { requireNotNull(it[0]) to it[1] }.toSortedMap()
        val build = version()?.takeIf { it.contains("DataFusion", true) } ?: return null
        val settings = settings()
        if (settings.isEmpty()) return null
        val namespaces = read(SQL_SCHEMAS, 2).map { row -> row.map { requireNotNull(it) } }.sortedBy { it.toString() }
        if (namespaces.isEmpty()) return null
        val tables = read(SQL_TABLES, 4)
        val columnRows = read(SQL_COLUMNS, 8)
        val viewRows = read(SQL_VIEWS, 4)
        val columns = columnRows.groupBy { it.take(3) }
        val views = viewRows.associate { it.take(3) to it[3] }
        val paths = tables.map { it.take(3) }
        if (paths.distinct().size != paths.size || columns.keys.any { it !in paths }) return null
        val relations = tables.map { row ->
            val path = row.take(3).map { requireNotNull(it) }
            val cols = columns[path].orEmpty().map { col -> NativeCatalogColumn(
                requireNotNull(col[3]), requireNotNull(col[4]?.toIntOrNull()), col[7],
                when (col[6]) { "YES" -> Nullability.NULLABLE; "NO" -> Nullability.NON_NULL; else -> Nullability.UNKNOWN }, col[5],
            ) }.sortedBy { it.ordinal }
            if (cols.map { it.ordinal }.distinct().size != cols.size || cols.map { it.name }.distinct().size != cols.size) return null
            if (cols.isNotEmpty() && (cols.first().ordinal !in 0..1 || cols.zipWithNext().any { (a, b) -> b.ordinal != a.ordinal + 1 })) return null
            NativeCatalogRelation(path, requireNotNull(row[3]), cols, views[path])
        }.sortedBy { it.path.toString() }
        // Routines carries namespaces; SHOW FUNCTIONS also lists table registrations omitted by routines.
        val routines = read(SQL_ROUTINES, 4)
        val functions = linkedMapOf<Pair<String, FunctionKind>, LiveFunction>()
        val shown = read(SQL_FUNCTIONS, 5)
        if (shown.isEmpty() || routines.isEmpty()) return null
        for (row in shown + routines.map { listOf(it[2], null, null, null, it[3]) }) {
            val name = row[0]?.takeIf { it.isNotBlank() } ?: return null
            val kind = when (row[4]?.uppercase()) {
                "SCALAR" -> FunctionKind.SCALAR
                "AGGREGATE" -> FunctionKind.AGGREGATE
                "WINDOW" -> FunctionKind.WINDOW
                "TABLE" -> FunctionKind.TABLE_VALUED
                else -> return null
            }
            val namespace = routines.firstOrNull { it[2] == name && it[3].equals(row[4], true) }?.take(2)?.map { requireNotNull(it) }.orEmpty()
            val stub = product == "spice" && kind == FunctionKind.SCALAR && name in setOf("vector_search", "text_search", "rrf", "rerank")
            functions[name to kind] = LiveFunction(FunctionDef(name, kind, nativeKind = if (stub) SCALAR_STUB_KIND else null), namespace)
        }
        if (functions.isEmpty()) return null
        // These endpoints have no metadata snapshot transaction. Reject changes seen across the read.
        fun stable(sql: String, width: Int, first: List<List<String?>>): Boolean =
            read(sql, width).sortedBy { it.toString() } == first.sortedBy { it.toString() }
        if (!stable(SQL_TABLES, 4, tables) || !stable(SQL_COLUMNS, 8, columnRows) || !stable(SQL_VIEWS, 4, viewRows) ||
            !stable(SQL_ROUTINES, 4, routines) || !stable(SQL_FUNCTIONS, 5, shown)) return null
        if (version() != build || settings() != settings) return null
        val productVersion = if (product == "spice") info?.version else Regex("DataFusion ([0-9]+\\.[0-9]+\\.[0-9]+)", RegexOption.IGNORE_CASE).find(build)?.groupValues?.get(1)
        // These settings affect name/type analysis. Extension settings can contain credentials.
        val relevant = settings.filterKeys { it.startsWith("datafusion.catalog.") || it.startsWith("datafusion.sql_parser.") }
        val catalog = NativeCatalog(product, build, relevant, namespaces, relations, "")
        val revision = ConnectionIdentity.of(listOf(product, productVersion, catalog.encoded(),
            functions.values.sortedBy { "${it.namespace}:${it.def.name}:${it.def.kind}" }.toString())).hash
        val inventory = LiveInventory(productVersion, functions.values.toList(), false, nativeCatalog = catalog.copy(revision = revision))
        val namespace = listOfNotNull(settings["datafusion.catalog.default_catalog"], settings["datafusion.catalog.default_schema"])
        return Harvest(inventory, SessionFacts(namespace = namespace.takeIf { it.size == 2 }.orEmpty()))
    }

    public companion object {
        public const val SQL_VERSION: String = "SELECT version() AS version"
        public const val SQL_SETTINGS: String = "SELECT name, value FROM information_schema.df_settings ORDER BY name"
        public const val SQL_SCHEMAS: String = "SELECT catalog_name, schema_name FROM information_schema.schemata ORDER BY catalog_name, schema_name"
        public const val SQL_TABLES: String = "SELECT table_catalog, table_schema, table_name, table_type FROM information_schema.tables ORDER BY table_catalog, table_schema, table_name"
        public const val SQL_COLUMNS: String = "SELECT table_catalog, table_schema, table_name, column_name, ordinal_position, column_default, is_nullable, data_type FROM information_schema.columns ORDER BY table_catalog, table_schema, table_name, ordinal_position"
        public const val SQL_VIEWS: String = "SELECT table_catalog, table_schema, table_name, definition FROM information_schema.views ORDER BY table_catalog, table_schema, table_name"
        public const val SQL_ROUTINES: String = "SELECT routine_catalog, routine_schema, routine_name, function_type FROM information_schema.routines ORDER BY routine_name, function_type"
        public const val SQL_FUNCTIONS: String = "SHOW FUNCTIONS"
    }
}

/** Spice has its own product version and registry, even when version() names the same DataFusion engine. */
public class SpiceCatalogHarvester : DatafusionCatalogHarvester("spice")
