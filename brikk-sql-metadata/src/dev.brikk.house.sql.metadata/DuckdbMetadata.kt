package dev.brikk.house.sql.metadata

/** DuckDB, one snapshot per vendored capture; 2.0.0 is the default core binds. */
val DUCKDB_METADATA: DialectMetadata =
    DialectMetadata("duckdb", DUCKDB_DUMPS.map(DuckdbDump::toSnapshot), defaultVersion = "2.0.0", docs = DUCKDB_DOCS)

/** DuckDB's built-in functions at the default release (`DuckdbDialect.functionCatalog`). */
val DUCKDB_FUNCTION_CATALOG: FunctionCatalog = DUCKDB_METADATA.default.functions
