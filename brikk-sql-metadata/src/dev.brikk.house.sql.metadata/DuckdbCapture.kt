package dev.brikk.house.sql.metadata

import kotlinx.serialization.json.Json

/**
 * A query over one DuckDB system table function, and how one of its result rows becomes a
 * row value. Every result column is text (lists are `to_json` arrays), so a live JDBC row, a
 * remote result-set row and a line of a vendored dump decode the same way: the capture tool
 * (tools/metadata-capture) writes `vendor/data/duckdb-<name>-<version>.tsv` from [sql], the
 * bundled snapshots are generated from those files, and a live harvest runs the same [sql] and
 * [decode] against the user's engine.
 */
class DuckdbCapture<out T>(
    val name: String,
    val sql: String,
    val columns: List<String>,
    private val decodeRow: (List<String?>) -> T,
) {
    /** Decodes one row given as its column values in [columns] order. */
    fun decode(row: List<String?>): T {
        require(row.size == columns.size) { "$name row has ${row.size} columns, expected ${columns.size}: $row" }
        return decodeRow(row)
    }
}

/**
 * One `duckdb_functions()` row: one overload of one function under one engine function type
 * (`scalar`, `aggregate`, `window`, `table`, `macro`, `table_macro`, `pragma`). Null list
 * elements are the engine's NULL types (macro parameters, for example). [database] and
 * [schema] say where the function lives and [internal] whether the engine built it in; the
 * defaults describe a built-in. User-defined functions are not internal, and temporary
 * macros live in database `temp`.
 */
data class DuckdbFunctionRow(
    val name: String,
    val type: String,
    val returnType: String? = null,
    val parameterTypes: List<String?> = emptyList(),
    val parameters: List<String> = emptyList(),
    val varargs: String? = null,
    val database: String = "system",
    val schema: String = "main",
    val internal: Boolean = true,
)

val DUCKDB_FUNCTIONS: DuckdbCapture<DuckdbFunctionRow> = DuckdbCapture(
    name = "functions",
    sql = """
        SELECT function_name, function_type, return_type,
               to_json(parameter_types)::VARCHAR AS parameter_types,
               to_json(parameters)::VARCHAR AS parameters,
               varargs, database_name, schema_name, internal::VARCHAR AS internal,
               alias_of, stability, description,
               to_json(examples)::VARCHAR AS examples
        FROM duckdb_functions()
        ORDER BY ALL
    """.trimIndent(),
    columns = listOf(
        "function_name", "function_type", "return_type", "parameter_types", "parameters", "varargs",
        "database_name", "schema_name", "internal", "alias_of", "stability", "description", "examples",
    ),
) { row ->
    DuckdbFunctionRow(
        name = row.required(0),
        type = row.required(1),
        returnType = row[2],
        parameterTypes = row.jsonList(3),
        parameters = row.jsonList(4).map { checkNotNull(it) { "null parameter name in $row" } },
        varargs = row[5],
        database = row.required(6),
        schema = row.required(7),
        internal = row.required(8).toBooleanStrict(),
    )
}

/** One `duckdb_keywords()` row; [category] is `reserved`, `unreserved`, `type_function` or `column_name`. */
data class DuckdbKeywordRow(val name: String, val category: String)

val DUCKDB_KEYWORDS: DuckdbCapture<DuckdbKeywordRow> = DuckdbCapture(
    name = "keywords",
    sql = "SELECT keyword_name, keyword_category FROM duckdb_keywords() ORDER BY ALL",
    columns = listOf("keyword_name", "keyword_category"),
) { row -> DuckdbKeywordRow(row.required(0), row.required(1)) }

/** One `duckdb_settings()` row, without the connection-dependent current value. */
data class DuckdbSettingRow(
    val name: String,
    val inputType: String,
    val scope: String,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
)

val DUCKDB_SETTINGS: DuckdbCapture<DuckdbSettingRow> = DuckdbCapture(
    name = "settings",
    sql = """
        SELECT name, input_type, scope, to_json(aliases)::VARCHAR AS aliases, description
        FROM duckdb_settings()
        ORDER BY ALL
    """.trimIndent(),
    columns = listOf("name", "input_type", "scope", "aliases", "description"),
) { row ->
    DuckdbSettingRow(
        name = row.required(0),
        inputType = row.required(1),
        scope = row.required(2),
        aliases = row.jsonList(3).map { checkNotNull(it) { "null alias in $row" } },
        description = row[4],
    )
}

/** One `duckdb_extensions()` row, without the installation-dependent columns. */
data class DuckdbExtensionRow(
    val name: String,
    val staticallyLinked: Boolean,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
)

val DUCKDB_EXTENSIONS: DuckdbCapture<DuckdbExtensionRow> = DuckdbCapture(
    name = "extensions",
    sql = """
        SELECT extension_name, (install_mode = 'STATICALLY_LINKED')::VARCHAR AS statically_linked,
               to_json(aliases)::VARCHAR AS aliases, description
        FROM duckdb_extensions()
        ORDER BY ALL
    """.trimIndent(),
    columns = listOf("extension_name", "statically_linked", "aliases", "description"),
) { row ->
    DuckdbExtensionRow(
        name = row.required(0),
        staticallyLinked = row.required(1).toBooleanStrict(),
        aliases = row.jsonList(2).map { checkNotNull(it) { "null alias in $row" } },
        description = row[3],
    )
}

/**
 * One built-in `duckdb_types()` name: a type or an alias of one, the [logicalType] it names and the engine's
 * [category] (`NUMERIC`, `STRING`, ...), which some types lack.
 */
data class DuckdbTypeRow(val name: String, val logicalType: String, val category: String? = null)

val DUCKDB_TYPES: DuckdbCapture<DuckdbTypeRow> = DuckdbCapture(
    name = "types",
    sql = """
        SELECT DISTINCT type_name, logical_type, type_category
        FROM duckdb_types()
        WHERE internal AND database_name = 'system'
        ORDER BY ALL
    """.trimIndent(),
    columns = listOf("type_name", "logical_type", "type_category"),
) { row -> DuckdbTypeRow(row.required(0), row.required(1), row[2]) }

/**
 * A `duckdb_functions()` row that appeared after `LOAD [extension]` on a fresh engine, where
 * `duckdb_extensions()` reported [extensionVersion] (empty for a statically linked one). The
 * vendored `duckdb-extension-functions-<version>.tsv` holds `extension`, `extension_version`,
 * then the [DUCKDB_FUNCTIONS] columns.
 */
data class DuckdbExtensionFunctionRow(
    val extension: String,
    val extensionVersion: String,
    val function: DuckdbFunctionRow,
)

private fun List<String?>.required(index: Int): String = checkNotNull(this[index]) { "column $index is null in $this" }

private fun List<String?>.jsonList(index: Int): List<String?> =
    this[index]?.let { Json.decodeFromString<List<String?>>(it) }.orEmpty()
