package dev.brikk.house.sql.shape

/** The kinds of objects whose definitions [definitionQuery] reads. */
enum class DefinedObject { CATALOG, SCHEMA, TABLE, VIEW, MATERIALIZED_VIEW, MACRO, SEQUENCE, INDEX }

/**
 * One catalog read for an object's definition: run [sql], then [definition] shapes its rows. Every
 * query only reads the catalog (`SHOW CREATE ...`, DuckDB's `duckdb_*()` tables); none runs user code.
 */
class DefinitionQuery internal constructor(val sql: String, private val shape: (List<Map<String, String?>>) -> String?) {
    /** The definition in [rows] (lower-case column labels to values), or null when they hold none. */
    fun definition(rows: List<Map<String, String?>>): String? = shape(rows)
}

/**
 * brikk-native: how [dialect] reads the definition of the [kind] object at [path] (namespace names top
 * first, then the object's name), or null when it cannot. Doris (`SHOW CREATE`; a catalog level is
 * optional, and a view in an external catalog is shown as a table, as Doris requires), Trino
 * (`SHOW CREATE`, fully qualified) and DuckDB (`[database, schema, name]` from `duckdb_tables()`,
 * `duckdb_views()`, `duckdb_sequences()`, `duckdb_indexes()`, and macros rebuilt from `duckdb_functions()`,
 * which does not report parameter defaults; the definition says so).
 */
fun definitionQuery(dialect: String, kind: DefinedObject, path: List<String>): DefinitionQuery? = when (dialect) {
    "doris" -> dorisQuery(kind, path)
    "trino" -> trinoQuery(kind, path)
    "duckdb" -> duckdbQuery(kind, path)
    else -> null
}

private fun dorisQuery(kind: DefinedObject, path: List<String>): DefinitionQuery? {
    fun quoted(names: List<String>) = names.joinToString(".") { "`${it.replace("`", "``")}`" }
    val sql = when (kind) {
        DefinedObject.CATALOG -> path.takeIf { it.size == 1 }?.let { "SHOW CREATE CATALOG ${quoted(it)}" }
        DefinedObject.SCHEMA -> path.takeIf { it.size in 1..2 }?.let { "SHOW CREATE DATABASE ${quoted(it)}" }
        DefinedObject.TABLE -> path.takeIf { it.size in 2..3 }?.let { "SHOW CREATE TABLE ${quoted(it)}" }
        DefinedObject.VIEW -> path.takeIf { it.size in 2..3 }?.let {
            val external = it.size == 3 && !it[0].equals("internal", ignoreCase = true)
            "SHOW CREATE ${if (external) "TABLE" else "VIEW"} ${quoted(it)}"
        }
        else -> null
    } ?: return null
    return DefinitionQuery(sql, ::showCreateDefinition)
}

private fun trinoQuery(kind: DefinedObject, path: List<String>): DefinitionQuery? {
    val (type, size) = when (kind) {
        DefinedObject.SCHEMA -> "SCHEMA" to 2
        DefinedObject.TABLE -> "TABLE" to 3
        DefinedObject.VIEW -> "VIEW" to 3
        DefinedObject.MATERIALIZED_VIEW -> "MATERIALIZED VIEW" to 3
        else -> return null
    }
    if (path.size != size) return null
    return DefinitionQuery("SHOW CREATE $type ${path.joinToString(".") { "\"${it.replace("\"", "\"\"")}\"" }}") { rows ->
        rows.firstOrNull()?.values?.firstOrNull()
    }
}

private fun duckdbQuery(kind: DefinedObject, path: List<String>): DefinitionQuery? {
    if (path.size != 3) return null
    val (database, schema, name) = path
    fun literal(value: String) = "'${value.replace("'", "''")}'"
    val where = "database_name = ${literal(database)} AND schema_name = ${literal(schema)}"
    fun catalogSql(table: String, nameColumn: String, extra: String = "") =
        DefinitionQuery("SELECT sql FROM $table() WHERE $where AND $nameColumn = ${literal(name)}$extra") { rows -> rows.firstOrNull()?.get("sql") }
    return when (kind) {
        DefinedObject.TABLE -> catalogSql("duckdb_tables", "table_name")
        DefinedObject.VIEW -> catalogSql("duckdb_views", "view_name", " AND NOT internal")
        DefinedObject.SEQUENCE -> catalogSql("duckdb_sequences", "sequence_name")
        DefinedObject.INDEX -> catalogSql("duckdb_indexes", "index_name")
        DefinedObject.MACRO -> DefinitionQuery(
            "SELECT function_type, array_to_string(parameters, ', ') AS parameters, macro_definition FROM duckdb_functions() " +
                "WHERE $where AND function_name = ${literal(name)} AND function_type IN ('macro', 'table_macro') ORDER BY function_oid",
        ) { rows ->
            rows.takeIf { it.isNotEmpty() }?.joinToString(
                ", ",
                prefix = "-- DuckDB does not report macro parameter defaults; any are omitted here.\nCREATE MACRO ${duckdbName(schema)}.${duckdbName(name)}",
                postfix = ";",
            ) { row ->
                val table = if (row["function_type"] == "table_macro") "TABLE " else ""
                "(${row["parameters"].orEmpty()}) AS $table${row["macro_definition"].orEmpty()}"
            }
        }
        else -> null
    }
}

private val PLAIN_NAME = Regex("[a-z_][a-z0-9_]*")

private fun duckdbName(name: String): String = if (PLAIN_NAME.matches(name)) name else "\"${name.replace("\"", "\"\"")}\""

/**
 * The definition a `SHOW CREATE` returned: the column whose label names it or whose value starts with
 * `CREATE`, else the second column (Doris returns the name first), else the first value.
 */
private fun showCreateDefinition(rows: List<Map<String, String?>>): String? {
    val row = rows.firstOrNull() ?: return null
    val values = row.entries.mapNotNull { (label, value) -> value?.trim()?.takeIf(String::isNotEmpty)?.let { label to it } }
    return values.firstOrNull { (label, value) -> "create" in label || value.startsWith("CREATE ", ignoreCase = true) }?.second
        ?: row.values.elementAtOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
        ?: values.firstOrNull()?.second
}
