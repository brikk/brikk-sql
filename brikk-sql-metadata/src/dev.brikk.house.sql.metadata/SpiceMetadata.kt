package dev.brikk.house.sql.metadata

/** The released v2.4.0+models inventory; model configuration and driver support are separate. */
val SPICE_METADATA: DialectMetadata = singleSnapshot(
    "spice", spiceSnapshot(), docs = EngineDocs(
        dialect = "spice", version = "current", release = "2.4.0",
        base = "https://spiceai.org/docs/reference/sql/",
        statements = listOf(
            DocsEntry("SELECT|WITH", "select", "Query syntax"),
            DocsEntry("EXPLAIN", "explain", "EXPLAIN"),
            DocsEntry("SHOW FUNCTIONS|TABLES|COLUMNS|ALL", "information_schema", "Information schema"),
            DocsEntry("PREPARE|EXECUTE", "prepared_statements", "Prepared statements"),
        ),
        tableFunctions = mapOf(
            "vector_search" to "search#vector-search-vector_search",
            "text_search" to "search#full-text-search-text_search",
            "rrf" to "search#reciprocal-rank-fusion-rrf",
            "rerank" to "search#reranking-rerank",
            "flatten_json" to "json#flatten_json",
            "flatten_json_properties" to "json#flatten_json_properties",
            "json_tree" to "json#json_tree",
        ),
    ),
)
