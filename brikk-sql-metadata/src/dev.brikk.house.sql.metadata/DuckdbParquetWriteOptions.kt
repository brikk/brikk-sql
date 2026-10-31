package dev.brikk.house.sql.metadata

/** Handwritten DuckDB 2.0 COPY PARQUET binding types, not read_parquet named parameters.
 * Source: parquet_extension.cpp::ParquetCopyGetOptions; row-group controls are writer options
 * handled by the generic COPY binder. ANY is intentionally not replaced with an invented
 * strict signature for the STRUCT/string value shapes described in the docs.
 */
val DUCKDB_PARQUET_WRITE_OPTION_TYPES: Map<String,String> = linkedMapOf(
    "COMPRESSION" to "VARCHAR", "CODEC" to "VARCHAR", "COMPRESSION_LEVEL" to "BIGINT",
    "FIELD_IDS" to "ANY", "KV_METADATA" to "ANY", "ENCRYPTION_CONFIG" to "ANY",
    "ROW_GROUP_SIZE" to "UBIGINT", "CHUNK_SIZE" to "UBIGINT", "ROW_GROUP_SIZE_BYTES" to "ANY",
    "ROW_GROUPS_PER_FILE" to "UBIGINT", "PARQUET_VERSION" to "VARCHAR",
    "DICTIONARY_SIZE_LIMIT" to "BIGINT", "STRING_DICTIONARY_PAGE_SIZE_LIMIT" to "UBIGINT",
    "DATA_PAGE_SIZE_LIMIT" to "UBIGINT", "WRITE_BLOOM_FILTER" to "BOOLEAN",
    "BLOOM_FILTER_FALSE_POSITIVE_RATIO" to "DOUBLE", "GEOPARQUET_VERSION" to "VARCHAR",
    "SHREDDING" to "ANY", "TIMESTAMP_IS_ADJUSTED_TO_UTC" to "ANY", "WRITE_TIMESTAMP_AS_INT96" to "BOOLEAN",
)

/** Documented value choices only; no complete option-validator or automatic IDE property UI. */
val DUCKDB_PARQUET_WRITE_OPTIONS: List<PropertyDef> = DUCKDB_PARQUET_WRITE_OPTION_TYPES.map { (name,type) ->
    PropertyDef(name,when(name) {
        "COMPRESSION","CODEC" -> listOf("uncompressed","snappy","gzip","zstd","brotli","lz4","lz4_raw")
        "PARQUET_VERSION" -> listOf("V1","V2")
        "GEOPARQUET_VERSION" -> listOf("NONE","V1","V2","BOTH")
        "TIMESTAMP_IS_ADJUSTED_TO_UTC" -> listOf("AUTO","TRUE","FALSE")
        "WRITE_BLOOM_FILTER","WRITE_TIMESTAMP_AS_INT96" -> listOf("true","false")
        else -> emptyList()
    },summary="COPY PARQUET writer option; native binding type $type. Value and combination checks remain native.",
        since=if(name in setOf("DATA_PAGE_SIZE_LIMIT","TIMESTAMP_IS_ADJUSTED_TO_UTC","WRITE_TIMESTAMP_AS_INT96","SHREDDING"))"2.0"else null)
}
