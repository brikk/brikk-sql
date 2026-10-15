package dev.brikk.house.sql.metadata

/** DuckDB, one snapshot per vendored capture; 1.5.6 is the default core binds. */
val DUCKDB_METADATA: DialectMetadata =
    DialectMetadata("duckdb", DUCKDB_DUMPS.map(DuckdbDump::toSnapshot), defaultVersion = "1.5.6", docs = DUCKDB_DOCS)

/** DuckDB's built-in functions at the default release (`DuckdbDialect.functionCatalog`). */
val DUCKDB_FUNCTION_CATALOG: FunctionCatalog = DUCKDB_METADATA.default.functions
