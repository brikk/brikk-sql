package dev.brikk.house.sql.metadata

private const val STATEMENTS = "sql/statements/"

/**
 * DuckDB's current documentation (1.5 on 2026-10-14; 1.4 is "lts"). The site has no `docs/1.5/` path yet:
 * the links follow the current release until it does.
 */
val DUCKDB_DOCS: EngineDocs = EngineDocs(
    dialect = "duckdb",
    version = "current",
    release = "1.5",
    base = "https://duckdb.org/docs/current/",
    statements = listOf(
        // Databases and extensions.
        DocsEntry("ATTACH [DATABASE] [IF NOT EXISTS] [OR REPLACE]", "core_extensions/ducklake", "DuckLake",
            "Attaches a DuckLake catalog: tables in Parquet files with their metadata in a catalog database.", literalPrefix = "ducklake:"),
        DocsEntry("ATTACH [DATABASE] [IF NOT EXISTS] [OR REPLACE]", "${STATEMENTS}attach", "ATTACH", "Attaches a database file or another database system."),
        DocsEntry("DETACH", "${STATEMENTS}attach", "DETACH"),
        DocsEntry("USE", "${STATEMENTS}use", "USE"),
        DocsEntry("UPDATE EXTENSIONS", "${STATEMENTS}update_extensions", "UPDATE EXTENSIONS"),
        DocsEntry("CREATE [OR REPLACE] EXTENSION REPOSITORY", "extensions/extension_repositories", "CREATE EXTENSION REPOSITORY",
            "Pins repository signing keys. Without explicit keys, execution fetches them from the prefix. Introduced in DuckDB 2.0."),
        DocsEntry("DROP EXTENSION REPOSITORY", "extensions/extension_repositories", "DROP EXTENSION REPOSITORY"),
        DocsEntry("[FORCE] INSTALL AND LOAD", "${STATEMENTS}load_and_install", "INSTALL AND LOAD",
            "Installs then loads an extension. A custom repository identifier and a string are not interchangeable."),
        DocsEntry("[FORCE] INSTALL|LOAD", "${STATEMENTS}load_and_install", "LOAD / INSTALL", "Installs or loads an extension."),
        DocsEntry("[FORCE] CHECKPOINT", "${STATEMENTS}checkpoint", "CHECKPOINT"),
        DocsEntry("VACUUM", "${STATEMENTS}vacuum", "VACUUM"),
        // Schema objects.
        DocsEntry("CREATE [OR REPLACE] [TEMP|TEMPORARY] TABLE", "${STATEMENTS}create_table", "CREATE TABLE"),
        DocsEntry("CREATE [OR REPLACE] [TEMP|TEMPORARY] [SECURE] [RECURSIVE] VIEW", "${STATEMENTS}create_view", "CREATE VIEW",
            "SECURE hides internal query plans and statistics for hidden rows, not the stored SQL definition. Introduced in DuckDB 2.0."),
        DocsEntry("CREATE [OR REPLACE] [TEMP|TEMPORARY] MACRO|FUNCTION", "${STATEMENTS}create_macro", "CREATE MACRO",
            "Creates a scalar or table macro: a function written in SQL."),
        DocsEntry("CREATE [OR REPLACE] [PERSISTENT|TEMPORARY] SECRET", "${STATEMENTS}create_secret", "CREATE SECRET"),
        DocsEntry("CREATE [UNIQUE] INDEX", "${STATEMENTS}create_index", "CREATE INDEX"),
        DocsEntry("CREATE [OR REPLACE] SCHEMA", "${STATEMENTS}create_schema", "CREATE SCHEMA"),
        DocsEntry("CREATE [OR REPLACE] [TEMP|TEMPORARY] SEQUENCE", "${STATEMENTS}create_sequence", "CREATE SEQUENCE"),
        DocsEntry("CREATE TYPE", "${STATEMENTS}create_type", "CREATE TYPE"),
        DocsEntry("ALTER TABLE", "${STATEMENTS}alter_table", "ALTER TABLE"),
        DocsEntry("ALTER VIEW", "${STATEMENTS}alter_view", "ALTER VIEW"),
        DocsEntry("DROP", "${STATEMENTS}drop", "DROP"),
        DocsEntry("COMMENT ON", "${STATEMENTS}comment_on", "COMMENT ON"),
        // Data.
        DocsEntry("COPY", "${STATEMENTS}copy", "COPY", "Copies data between a table or query and files."),
        DocsEntry("EXPORT DATABASE", "${STATEMENTS}export", "EXPORT DATABASE"),
        DocsEntry("IMPORT DATABASE", "${STATEMENTS}export", "IMPORT DATABASE"),
        DocsEntry("INSERT [OR REPLACE|IGNORE] INTO", "${STATEMENTS}insert", "INSERT"),
        DocsEntry("DELETE", "${STATEMENTS}delete", "DELETE"),
        DocsEntry("UPDATE", "${STATEMENTS}update", "UPDATE"),
        DocsEntry("MERGE INTO", "${STATEMENTS}merge_into", "MERGE INTO"),
        // Queries and the session.
        DocsEntry("SELECT", "${STATEMENTS}select", "SELECT"),
        DocsEntry("SUMMARIZE", "${STATEMENTS}summarize", "SUMMARIZE"),
        DocsEntry("DESC|DESCRIBE", "${STATEMENTS}describe", "DESCRIBE"),
        DocsEntry("SHOW", "${STATEMENTS}show", "SHOW"),
        DocsEntry("EXPLAIN", "guides/meta/explain", "EXPLAIN"),
        DocsEntry("ANALYZE", "${STATEMENTS}analyze", "ANALYZE"),
        DocsEntry("CALL", "${STATEMENTS}call", "CALL"),
        DocsEntry("PREPARE|EXECUTE|DEALLOCATE", "${STATEMENTS}prepare", "PREPARE, EXECUTE, and DEALLOCATE"),
        DocsEntry("SET|RESET [GLOBAL|SESSION|LOCAL] VARIABLE", "${STATEMENTS}set_variable", "SET VARIABLE"),
        DocsEntry("SET|RESET", "${STATEMENTS}set", "SET", "Changes a configuration option."),
        DocsEntry("BEGIN|COMMIT|ROLLBACK|ABORT|END [TRANSACTION]", "${STATEMENTS}transactions", "Transaction Management"),
        DocsEntry("START TRANSACTION", "${STATEMENTS}transactions", "Transaction Management"),
        // Clauses.
        DocsEntry("PIVOT", "${STATEMENTS}pivot", "PIVOT", "Turns distinct values of a column into columns.", clause = true),
        DocsEntry("UNPIVOT", "${STATEMENTS}unpivot", "UNPIVOT", "Turns columns into rows.", clause = true),
    ),
    tableFunctions = (
        listOf("read_csv", "read_csv_auto", "sniff_csv").associateWith { "data/csv/overview" } +
            listOf("read_json", "read_json_auto", "read_json_objects", "read_json_objects_auto", "read_ndjson", "read_ndjson_auto",
                "read_ndjson_objects").associateWith { "data/json/overview" } +
            listOf("read_parquet", "parquet_scan", "parquet_metadata", "parquet_schema", "parquet_file_metadata", "parquet_kv_metadata",
                "parquet_full_metadata", "parquet_bloom_probe").associateWith { "data/parquet/overview" } +
            listOf("duckdb_columns", "duckdb_constraints", "duckdb_databases", "duckdb_dependencies", "duckdb_extensions", "duckdb_functions",
                "duckdb_indexes", "duckdb_keywords", "duckdb_memory", "duckdb_optimizers", "duckdb_schemas", "duckdb_secrets", "duckdb_sequences",
                "duckdb_settings", "duckdb_tables", "duckdb_temporary_files", "duckdb_types", "duckdb_variables", "duckdb_views")
                .associateWith { "sql/meta/duckdb_table_functions" } +
            mapOf("glob" to "sql/functions/utility", "unnest" to "sql/query_syntax/unnest",
                "duckdb_extension_repositories" to "extensions/extension_repositories")
        ),
)
