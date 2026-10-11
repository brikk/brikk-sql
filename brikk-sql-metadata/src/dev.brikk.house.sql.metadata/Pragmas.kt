package dev.brikk.house.sql.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A `PRAGMA name` / `PRAGMA name(args)` statement (DuckDB). Pragmas are statements, not
 * functions; `PRAGMA name = value` assigns a setting and is described by [SettingDef].
 * [overloads] use the function shape: positional arguments, return type "ANY".
 * [tableFunction] names the table function that returns the same data (`pragma_table_info`).
 * [extension] is set when an extension provides the pragma.
 */
@Serializable
data class PragmaDef(
    val name: String,
    val overloads: List<FunctionOverload> = emptyList(),
    @SerialName("table_function") val tableFunction: String? = null,
    val extension: String? = null,
)

/**
 * A setting assignable with `SET` (and DuckDB's `PRAGMA name = value`). [valueType] and
 * [scope] are the engine's own spellings (DuckDB `BIGINT`, `GLOBAL`/`LOCAL`).
 */
@Serializable
data class SettingDef(
    val name: String,
    @SerialName("value_type") val valueType: String? = null,
    val scope: String? = null,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
)
