package dev.brikk.house.sql.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One output column of a table function; [type] is a plain SQL type spelling. */
@Serializable
data class ColumnDef(val name: String, val type: String)

/**
 * A property key a table function or a `PROPERTIES` list takes: [values] lists its values when they form a
 * known set, [summary] says what it does in a sentence, and [since] is the earliest release (line) that has it,
 * when known.
 */
@Serializable
data class PropertyDef(
    val name: String,
    val values: List<String> = emptyList(),
    val summary: String? = null,
    val since: String? = null,
) {
    // Binary compatibility (AGENTS.md): the constructor and copy from before summary and since.
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(name: String, values: List<String> = emptyList()) : this(name, values, null, null)

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    fun copy(name: String = this.name, values: List<String> = this.values): PropertyDef = copy(name, values, summary, since)
}

/** The columns a table function returns, as far as they are known without running it. */
@Serializable
sealed interface OutputSchema {
    /** Always the same columns. */
    @Serializable
    @SerialName("fixed")
    data class Fixed(val columns: List<ColumnDef>) : OutputSchema

    /**
     * Columns chosen by the literal value of property [key] (lowercase [variants] keys);
     * [default] is the variant used when the property is absent, if the engine has one.
     */
    @Serializable
    @SerialName("by_property")
    data class ByProperty(
        val key: String,
        val variants: Map<String, List<ColumnDef>>,
        val default: String? = null,
    ) : OutputSchema

    /** Columns depend on external data (a file, a remote table) and are unknown statically. */
    @Serializable
    @SerialName("open")
    data object Open : OutputSchema
}

/**
 * Call-site knowledge of a table function taking `"key" = "value"` properties (Doris
 * `s3("uri" = "...", "format" = "csv")`). [queryable] is false for registered table functions
 * that cannot be used as a FROM relation (stream-load and ingest functions). Look entries up
 * with [MetadataSnapshot.tableFunction], which follows the dialect's name policy.
 */
@Serializable
data class TableFunctionInfo(
    val name: String,
    val properties: List<PropertyDef> = emptyList(),
    val schema: OutputSchema = OutputSchema.Open,
    val queryable: Boolean = true,
)
