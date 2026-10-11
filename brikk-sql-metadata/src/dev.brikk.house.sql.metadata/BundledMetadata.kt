package dev.brikk.house.sql.metadata

/** DuckDB, one snapshot per vendored capture; 1.5.6 is the default core binds. */
val DUCKDB_METADATA: DialectMetadata =
    DialectMetadata("duckdb", DUCKDB_DUMPS.map(DuckdbDump::toSnapshot), defaultVersion = "1.5.6")

/** DuckDB's built-in functions at the default release (`DuckdbDialect.functionCatalog`). */
val DUCKDB_FUNCTION_CATALOG: FunctionCatalog = DUCKDB_METADATA.default.functions

val TRINO_METADATA: DialectMetadata =
    DialectMetadata("trino", listOf(MetadataSnapshot("483", TRINO_FUNCTION_CATALOG, TRINO_483_KEYWORDS)), defaultVersion = "483")

/** One combined snapshot; [FunctionDef.sinceVersion] carries Doris's documentation tiers. */
val DORIS_METADATA: DialectMetadata =
    DialectMetadata("doris", listOf(MetadataSnapshot("4.1.4", DORIS_FUNCTION_CATALOG, DORIS_4_1_4_KEYWORDS)), defaultVersion = "4.1.4")

val STARROCKS_METADATA: DialectMetadata =
    DialectMetadata("starrocks", listOf(MetadataSnapshot("4.1.4", STARROCKS_FUNCTION_CATALOG)), defaultVersion = "4.1.4")

val CLICKHOUSE_METADATA: DialectMetadata =
    DialectMetadata("clickhouse", listOf(MetadataSnapshot("26.5.1.1", CLICKHOUSE_FUNCTION_CATALOG, CLICKHOUSE_26_5_1_1_KEYWORDS)), defaultVersion = "26.5.1.1")
