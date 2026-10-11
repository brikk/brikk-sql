package dev.brikk.house.sql.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An extension the engine knows about (DuckDB `duckdb_extensions()`). [staticallyLinked]
 * extensions are built in, so their functions are part of the base catalog.
 */
@Serializable
data class ExtensionDef(
    val name: String,
    @SerialName("statically_linked") val staticallyLinked: Boolean = false,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
)
