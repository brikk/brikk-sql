package dev.brikk.house.sql.metadata

/** DuckDB, one snapshot per vendored capture; 1.5.6 is the default core binds. */
val DUCKDB_METADATA: DialectMetadata =
    DialectMetadata("duckdb", DUCKDB_DUMPS.map(DuckdbDump::toSnapshot), defaultVersion = "1.5.6")

/** DuckDB's built-in functions at the default release (`DuckdbDialect.functionCatalog`). */
val DUCKDB_FUNCTION_CATALOG: FunctionCatalog = DUCKDB_METADATA.default.functions

val TRINO_METADATA: DialectMetadata =
    single("trino", MetadataSnapshot("483", TRINO_FUNCTION_CATALOG, TRINO_483_KEYWORDS, grammarForms = TRINO_GRAMMAR_FORMS))

/** One combined snapshot; [FunctionDef.sinceVersion] carries Doris's documentation tiers. */
val DORIS_METADATA: DialectMetadata = single(
    "doris",
    MetadataSnapshot(
        "4.1.4",
        DORIS_FUNCTION_CATALOG,
        DORIS_4_1_4_KEYWORDS,
        tableFunctions = DORIS_TABLE_FUNCTIONS,
        grammarForms = DORIS_GRAMMAR_FORMS,
    ),
)

val STARROCKS_METADATA: DialectMetadata = single(
    "starrocks",
    MetadataSnapshot("4.1.4", STARROCKS_FUNCTION_CATALOG, grammarForms = STARROCKS_GRAMMAR_FORMS),
)

val CLICKHOUSE_METADATA: DialectMetadata =
    single("clickhouse", MetadataSnapshot("26.5.1.1", CLICKHOUSE_FUNCTION_CATALOG, CLICKHOUSE_26_5_1_1_KEYWORDS))

private fun single(dialect: String, snapshot: MetadataSnapshot) =
    DialectMetadata(dialect, listOf(snapshot), defaultVersion = snapshot.engineVersion)
