package dev.brikk.house.sql.metadata

/** The DuckDB release core binds through `DuckdbDialect.functionCatalog`. */
private const val DUCKDB_DEFAULT_VERSION = "1.5.6"

/** DuckDB's built-in functions at the default release. */
val DUCKDB_FUNCTION_CATALOG: FunctionCatalog =
    DUCKDB_DUMPS.single { it.engineVersion == DUCKDB_DEFAULT_VERSION }.functionCatalog()
