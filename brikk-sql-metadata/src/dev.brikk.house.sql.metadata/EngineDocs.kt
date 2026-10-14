package dev.brikk.house.sql.metadata

import kotlinx.serialization.Serializable

/**
 * A documented SQL construct: the words naming it, as a pattern (`A|B` accepts either, `[...]` makes
 * words optional: `CREATE [OR REPLACE] VIEW`), the page documenting it relative to [EngineDocs.base], its
 * [title] and an optional one-line [summary]. A [clause] is named anywhere in a statement (`INTO OUTFILE`);
 * otherwise the words begin the statement. With a [literalPrefix], the words must be followed by a string
 * literal starting with it (`ATTACH 'ducklake:...'`).
 */
@Serializable
data class DocsEntry(
    val pattern: String,
    val path: String,
    val title: String,
    val summary: String? = null,
    val clause: Boolean = false,
    val literalPrefix: String? = null,
)

/**
 * Where one engine's documentation for a pinned release line lives: [base] (ending in `/`) holds the pages
 * of [version]. [statements] are tried in order, so a specific form comes before a general one
 * (`SET PASSWORD` before `SET`); [tableFunctions] maps a table function's lowercase name to its page, and
 * [engines] a table engine's (`ENGINE = MergeTree`).
 */
@Serializable
data class EngineDocs(
    val dialect: String,
    val version: String,
    val base: String,
    val statements: List<DocsEntry>,
    val tableFunctions: Map<String, String> = emptyMap(),
    val engines: Map<String, String> = emptyMap(),
) {
    init {
        require(base.endsWith("/")) { "Docs base must end with '/': $base" }
    }

    /** The absolute URL of [path]. */
    fun url(path: String): String = base + path
}

/** The bundled engine documentation, by core dialect name. */
val ENGINE_DOCS: Map<String, EngineDocs> = listOf(DORIS_DOCS, CLICKHOUSE_DOCS, DUCKDB_DOCS).associateBy { it.dialect }
