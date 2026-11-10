package dev.brikk.house.sql.metadata

import kotlinx.serialization.json.Json

/** Registry observations for an exact built-in overload, not optimizer or execution proof.
 * DuckDB 2.0 exposes no null-handling/default-argument/positional-split/table-output columns.
 * Keep those facts unknown rather than infer them from these names or stability flags.
 * This separate capture leaves published FunctionDef/FunctionOverload/row JVM shapes intact.
 */
data class DuckdbFunctionProperties(
    val database: String,
    val schema: String,
    val name: String,
    val nativeKind: String,
    val returnType: String?,
    val parameterTypes: List<String?>,
    val parameters: List<String>,
    val varargs: String?,
    val aliasOf: String?,
    val stability: String?,
    val hasSideEffects: Boolean?,
    val extension: String?,
    val description: String?,
    val examples: List<String>,
)

/** Separate from DUCKDB_FUNCTIONS so old vendored TSVs and live consumers keep their schema.
 * User comments, tags and macro bodies are deliberately excluded from static captures.
 */
val DUCKDB_FUNCTION_PROPERTIES: DuckdbCapture<DuckdbFunctionProperties> = DuckdbCapture(
    name = "function-properties",
    sql = """
        SELECT database_name,schema_name,function_name,function_type,return_type,
               to_json(parameter_types)::VARCHAR AS parameter_types,
               to_json(parameters)::VARCHAR AS parameters,varargs,alias_of,stability,
               has_side_effects::VARCHAR AS has_side_effects,extension_name,description,
               to_json(examples)::VARCHAR AS examples
        FROM duckdb_functions() WHERE internal ORDER BY ALL
    """.trimIndent(),
    columns = listOf("database_name", "schema_name", "function_name", "function_type", "return_type", "parameter_types",
        "parameters", "varargs", "alias_of", "stability", "has_side_effects", "extension_name", "description", "examples"),
) { row ->
    fun required(index: Int) = requireNotNull(row[index])
    DuckdbFunctionProperties(required(0), required(1), required(2), required(3), row[4],
        row[5]?.let { Json.decodeFromString<List<String?>>(it) }.orEmpty(),
        row[6]?.let { Json.decodeFromString<List<String>>(it) }.orEmpty(), row[7], row[8], row[9], row[10]?.toBooleanStrict(), row[11], row[12],
        row[13]?.let { Json.decodeFromString<List<String>>(it) }.orEmpty())
}
