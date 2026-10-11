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
 * elements are the engine's NULL types (macro parameters, for example).
 */
data class DuckdbFunctionRow(
    val name: String,
    val type: String,
    val returnType: String? = null,
    val parameterTypes: List<String?> = emptyList(),
    val parameters: List<String> = emptyList(),
    val varargs: String? = null,
)

val DUCKDB_FUNCTIONS: DuckdbCapture<DuckdbFunctionRow> = DuckdbCapture(
    name = "functions",
    sql = """
        SELECT function_name, function_type, return_type,
               to_json(parameter_types)::VARCHAR AS parameter_types,
               to_json(parameters)::VARCHAR AS parameters,
               varargs, alias_of, stability, description,
               to_json(examples)::VARCHAR AS examples
        FROM duckdb_functions()
        ORDER BY ALL
    """.trimIndent(),
    columns = listOf(
        "function_name", "function_type", "return_type", "parameter_types", "parameters",
        "varargs", "alias_of", "stability", "description", "examples",
    ),
) { row ->
    DuckdbFunctionRow(
        name = row.required(0),
        type = row.required(1),
        returnType = row[2],
        parameterTypes = row.jsonList(3),
        parameters = row.jsonList(4).map { checkNotNull(it) { "null parameter name in $row" } },
        varargs = row[5],
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

private fun List<String?>.required(index: Int): String = checkNotNull(this[index]) { "column $index is null in $this" }

private fun List<String?>.jsonList(index: Int): List<String?> =
    this[index]?.let { Json.decodeFromString<List<String?>>(it) }.orEmpty()
