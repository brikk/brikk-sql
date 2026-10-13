package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.sql

/** The kinds of objects whose definitions [definitionQuery] reads. */
enum class DefinedObject { CATALOG, SCHEMA, TABLE, VIEW, MATERIALIZED_VIEW, MACRO, SEQUENCE, INDEX, DICTIONARY }

/**
 * One catalog read for an object's definition: run [sql], then [definition] shapes its rows. Every
 * query only reads the catalog (`SHOW CREATE ...`, DuckDB's built-in `duckdb_*()` table functions) and
 * calls no other function, so none runs user code. [listColumns] are read as lists of strings.
 */
class DefinitionQuery internal constructor(
    val sql: String,
    val listColumns: Set<String> = emptySet(),
    private val shape: (List<Map<String, Any?>>) -> String?,
) {
    /** The definition in [rows] (lower-case column labels to strings, or lists of strings for [listColumns]), or null when they hold none. */
    fun definition(rows: List<Map<String, Any?>>): String? = shape(rows)
}

/**
 * brikk-native: how [dialect] reads the definition of the [kind] object at [path] (namespace names top
 * first, then the object's name), or null when it cannot. Doris (`SHOW CREATE`; a catalog level is
 * optional, and a view in an external catalog is shown as a table, as Doris requires), Trino
 * (`SHOW CREATE`, fully qualified) and DuckDB (`[database, schema, name]` from `duckdb_tables()`,
 * `duckdb_views()`, `duckdb_sequences()`, `duckdb_indexes()`, and macros rebuilt from `duckdb_functions()`,
 * which does not report parameter defaults; the definition says so), and ClickHouse (`SHOW CREATE` with
 * `[database, name]`, each part quoted as its core dialect quotes identifiers; a materialized view is
 * shown as a table, since ClickHouse rejects `SHOW CREATE MATERIALIZED VIEW`).
 */
fun definitionQuery(dialect: String, kind: DefinedObject, path: List<String>): DefinitionQuery? = when (dialect) {
    "doris" -> dorisQuery(kind, path)
    "trino" -> trinoQuery(kind, path)
    "duckdb" -> duckdbQuery(kind, path)
    "clickhouse" -> clickhouseQuery(kind, path)
    else -> null
}

private fun clickhouseQuery(kind: DefinedObject, path: List<String>): DefinitionQuery? {
    val (type, size) = when (kind) {
        DefinedObject.SCHEMA -> "DATABASE" to 1
        DefinedObject.TABLE, DefinedObject.MATERIALIZED_VIEW -> "TABLE" to 2
        DefinedObject.VIEW -> "VIEW" to 2
        DefinedObject.DICTIONARY -> "DICTIONARY" to 2
        else -> return null
    }
    if (path.size != size) return null
    val names = path.joinToString(".") { Identifier(args("this" to it, "quoted" to true)).sql("clickhouse") }
    return DefinitionQuery("SHOW CREATE $type $names", shape = ::showCreateDefinition)
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
    return DefinitionQuery(sql, shape = ::showCreateDefinition)
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
        rows.firstOrNull()?.values?.firstOrNull() as? String
    }
}

private fun duckdbQuery(kind: DefinedObject, path: List<String>): DefinitionQuery? {
    if (path.size != 3) return null
    val (database, schema, name) = path
    fun literal(value: String) = "'${value.replace("'", "''")}'"
    val where = "database_name = ${literal(database)} AND schema_name = ${literal(schema)}"
    // A user macro of the same name shadows an unqualified call, and DuckDB's own macros (its array_to_string)
    // call others unqualified: only built-in table functions are called, qualified, and the rest is shaped here.
    fun catalogSql(table: String, nameColumn: String, extra: String = "") =
        DefinitionQuery("SELECT sql FROM $SYSTEM$table() WHERE $where AND $nameColumn = ${literal(name)}$extra") { rows -> rows.firstOrNull()?.get("sql") as? String }
    return when (kind) {
        DefinedObject.TABLE -> catalogSql("duckdb_tables", "table_name")
        DefinedObject.VIEW -> catalogSql("duckdb_views", "view_name", " AND NOT internal")
        DefinedObject.SEQUENCE -> catalogSql("duckdb_sequences", "sequence_name")
        DefinedObject.INDEX -> catalogSql("duckdb_indexes", "index_name")
        DefinedObject.MACRO -> DefinitionQuery(
            "SELECT function_type, parameters, macro_definition FROM ${SYSTEM}duckdb_functions() " +
                "WHERE $where AND function_name = ${literal(name)} AND function_type IN ('macro', 'table_macro') ORDER BY function_oid",
            listColumns = setOf("parameters"),
        ) { rows ->
            rows.takeIf { it.isNotEmpty() }?.joinToString(
                ", ",
                prefix = "-- DuckDB does not report macro parameter defaults; any are omitted here.\nCREATE MACRO ${duckdbQuoted(schema)}.${duckdbQuoted(name)}",
                postfix = ";",
            ) { row ->
                val table = if (row["function_type"] == "table_macro") "TABLE " else ""
                val parameters = (row["parameters"] as? List<*>).orEmpty().joinToString(", ") { duckdbQuoted(it.toString()) }
                "($parameters) AS $table${row["macro_definition"] as? String ?: ""}"
            }
        }
        else -> null
    }
}

private const val SYSTEM = "system.main."

private fun duckdbQuoted(name: String): String = "\"${name.replace("\"", "\"\"")}\""

/**
 * The definition a `SHOW CREATE` returned: the column whose label names it or whose value starts with
 * `CREATE`, else the second column (Doris returns the name first), else the first value.
 */
private fun showCreateDefinition(rows: List<Map<String, Any?>>): String? {
    val row = rows.firstOrNull() ?: return null
    val values = row.entries.mapNotNull { (label, value) -> (value as? String)?.trim()?.takeIf(String::isNotEmpty)?.let { label to it } }
    return values.firstOrNull { (label, value) -> "create" in label || value.startsWith("CREATE ", ignoreCase = true) }?.second
        ?: (row.values.elementAtOrNull(1) as? String)?.trim()?.takeIf(String::isNotEmpty)
        ?: values.firstOrNull()?.second
}
