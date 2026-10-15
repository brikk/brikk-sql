package dev.brikk.house.sql.metadata

private const val STATEMENTS = "sql-reference/sql-statements/"

/**
 * StarRocks' latest documentation, its unversioned pages (4.1 on 2026-10-14). The site has no `docs/4.1/`
 * path yet: the links follow the latest release until it does.
 */
val STARROCKS_DOCS: EngineDocs = EngineDocs(
    dialect = "starrocks",
    version = "latest",
    release = "4.1",
    base = "https://docs.starrocks.io/docs/",
    statements = listOf(
        // Tables, views and materialized views.
        DocsEntry("CREATE [EXTERNAL] [TEMPORARY] TABLE", "${STATEMENTS}table_bucket_part_index/CREATE_TABLE/", "CREATE TABLE",
            "Creates a table: its columns, table type, partitioning, bucketing and properties."),
        DocsEntry("ALTER TABLE", "${STATEMENTS}table_bucket_part_index/ALTER_TABLE/", "ALTER TABLE"),
        DocsEntry("DROP [TEMPORARY] TABLE", "${STATEMENTS}table_bucket_part_index/DROP_TABLE/", "DROP TABLE"),
        DocsEntry("TRUNCATE TABLE", "${STATEMENTS}table_bucket_part_index/TRUNCATE_TABLE/", "TRUNCATE TABLE"),
        DocsEntry("CREATE INDEX", "${STATEMENTS}table_bucket_part_index/CREATE_INDEX/", "CREATE INDEX"),
        DocsEntry("DROP INDEX", "${STATEMENTS}table_bucket_part_index/DROP_INDEX/", "DROP INDEX"),
        DocsEntry("CREATE MATERIALIZED VIEW", "${STATEMENTS}materialized_view/CREATE_MATERIALIZED_VIEW/", "CREATE MATERIALIZED VIEW",
            "Creates a materialized view, refreshed asynchronously or synchronously."),
        DocsEntry("ALTER MATERIALIZED VIEW", "${STATEMENTS}materialized_view/ALTER_MATERIALIZED_VIEW/", "ALTER MATERIALIZED VIEW"),
        DocsEntry("DROP MATERIALIZED VIEW", "${STATEMENTS}materialized_view/DROP_MATERIALIZED_VIEW/", "DROP MATERIALIZED VIEW"),
        DocsEntry("REFRESH MATERIALIZED VIEW", "${STATEMENTS}materialized_view/REFRESH_MATERIALIZED_VIEW/", "REFRESH MATERIALIZED VIEW"),
        DocsEntry("CANCEL REFRESH MATERIALIZED VIEW", "${STATEMENTS}materialized_view/CANCEL_REFRESH_MATERIALIZED_VIEW/", "CANCEL REFRESH MATERIALIZED VIEW"),
        DocsEntry("REFRESH EXTERNAL TABLE", "${STATEMENTS}table_bucket_part_index/REFRESH_EXTERNAL_TABLE/", "REFRESH EXTERNAL TABLE"),
        DocsEntry("CREATE [OR REPLACE] VIEW", "${STATEMENTS}View/CREATE_VIEW/", "CREATE VIEW"),
        DocsEntry("ALTER VIEW", "${STATEMENTS}View/ALTER_VIEW/", "ALTER VIEW"),
        DocsEntry("DROP VIEW", "${STATEMENTS}View/DROP_VIEW/", "DROP VIEW"),
        DocsEntry("SHOW CREATE TABLE", "${STATEMENTS}table_bucket_part_index/SHOW_CREATE_TABLE/", "SHOW CREATE TABLE"),
        DocsEntry("SHOW [FULL] TABLES", "${STATEMENTS}table_bucket_part_index/SHOW_TABLES/", "SHOW TABLES"),
        DocsEntry("DESC|DESCRIBE", "${STATEMENTS}table_bucket_part_index/DESCRIBE/", "DESC"),
        // Catalogs, databases and the session.
        DocsEntry("CREATE EXTERNAL CATALOG", "${STATEMENTS}Catalog/CREATE_EXTERNAL_CATALOG/", "CREATE EXTERNAL CATALOG",
            "Connects an external data source as a catalog."),
        DocsEntry("DROP CATALOG", "${STATEMENTS}Catalog/DROP_CATALOG/", "DROP CATALOG"),
        DocsEntry("SET CATALOG", "${STATEMENTS}Catalog/SET_CATALOG/", "SET CATALOG"),
        DocsEntry("SHOW CATALOGS", "${STATEMENTS}Catalog/SHOW_CATALOGS/", "SHOW CATALOGS"),
        DocsEntry("CREATE DATABASE", "${STATEMENTS}Database/CREATE_DATABASE/", "CREATE DATABASE"),
        DocsEntry("ALTER DATABASE", "${STATEMENTS}Database/ALTER_DATABASE/", "ALTER DATABASE"),
        DocsEntry("DROP DATABASE", "${STATEMENTS}Database/DROP_DATABASE/", "DROP DATABASE"),
        DocsEntry("SHOW DATABASES", "${STATEMENTS}Database/SHOW_DATABASES/", "SHOW DATABASES"),
        DocsEntry("USE", "${STATEMENTS}Database/USE/", "USE"),
        DocsEntry("SET PASSWORD", "${STATEMENTS}account-management/SET_PASSWORD/", "SET PASSWORD"),
        DocsEntry("SET DEFAULT ROLE", "${STATEMENTS}account-management/SET_DEFAULT_ROLE/", "SET DEFAULT ROLE"),
        DocsEntry("SET ROLE", "${STATEMENTS}account-management/SET_ROLE/", "SET ROLE"),
        DocsEntry("SET", "${STATEMENTS}cluster-management/config_vars/SET/", "SET", "Sets a session or global variable."),
        // Loading, unloading and tasks.
        DocsEntry("INSERT INTO|OVERWRITE", "${STATEMENTS}loading_unloading/INSERT/", "INSERT", "Loads rows from a query or values into a table."),
        DocsEntry("DELETE", "${STATEMENTS}table_bucket_part_index/DELETE/", "DELETE"),
        DocsEntry("UPDATE", "${STATEMENTS}table_bucket_part_index/UPDATE/", "UPDATE"),
        DocsEntry("EXPORT TABLE", "${STATEMENTS}loading_unloading/unloading/EXPORT/", "EXPORT"),
        DocsEntry("SUBMIT TASK", "${STATEMENTS}loading_unloading/ETL/SUBMIT_TASK/", "SUBMIT TASK", "Runs a statement as an asynchronous task."),
        DocsEntry("ALTER TASK", "${STATEMENTS}loading_unloading/ETL/ALTER_TASK/", "ALTER TASK"),
        DocsEntry("DROP TASK", "${STATEMENTS}loading_unloading/ETL/DROP_TASK/", "DROP TASK"),
        DocsEntry("CREATE [OR REPLACE] PIPE", "${STATEMENTS}loading_unloading/pipe/CREATE_PIPE/", "CREATE PIPE",
            "Loads files continuously into a table as they arrive."),
        DocsEntry("ALTER PIPE", "${STATEMENTS}loading_unloading/pipe/ALTER_PIPE/", "ALTER PIPE"),
        DocsEntry("DROP PIPE", "${STATEMENTS}loading_unloading/pipe/DROP_PIPE/", "DROP PIPE"),
        DocsEntry("SHOW PIPES", "${STATEMENTS}loading_unloading/pipe/SHOW_PIPES/", "SHOW PIPES"),
        DocsEntry("CREATE ROUTINE LOAD", "${STATEMENTS}loading_unloading/routine_load/CREATE_ROUTINE_LOAD/", "CREATE ROUTINE LOAD"),
        DocsEntry("ALTER ROUTINE LOAD", "${STATEMENTS}loading_unloading/routine_load/ALTER_ROUTINE_LOAD/", "ALTER ROUTINE LOAD"),
        DocsEntry("PAUSE ROUTINE LOAD", "${STATEMENTS}loading_unloading/routine_load/PAUSE_ROUTINE_LOAD/", "PAUSE ROUTINE LOAD"),
        DocsEntry("RESUME ROUTINE LOAD", "${STATEMENTS}loading_unloading/routine_load/RESUME_ROUTINE_LOAD/", "RESUME ROUTINE LOAD"),
        DocsEntry("STOP ROUTINE LOAD", "${STATEMENTS}loading_unloading/routine_load/STOP_ROUTINE_LOAD/", "STOP ROUTINE LOAD"),
        DocsEntry("SHOW [ALL] ROUTINE LOAD", "${STATEMENTS}loading_unloading/routine_load/SHOW_ROUTINE_LOAD/", "SHOW ROUTINE LOAD"),
        DocsEntry("LOAD LABEL", "${STATEMENTS}loading_unloading/BROKER_LOAD/", "BROKER LOAD"),
        // Queries, statistics and functions.
        DocsEntry("SELECT", "${STATEMENTS}table_bucket_part_index/SELECT/", "SELECT"),
        DocsEntry("EXPLAIN ANALYZE", "${STATEMENTS}cluster-management/plan_profile/EXPLAIN_ANALYZE/", "EXPLAIN ANALYZE"),
        DocsEntry("EXPLAIN", "${STATEMENTS}cluster-management/plan_profile/EXPLAIN/", "EXPLAIN"),
        DocsEntry("ANALYZE [FULL|SAMPLE] TABLE", "${STATEMENTS}cbo_stats/ANALYZE_TABLE/", "ANALYZE TABLE"),
        DocsEntry("CREATE [GLOBAL] [AGGREGATE|TABLE] FUNCTION", "${STATEMENTS}Function/CREATE_FUNCTION/", "CREATE FUNCTION"),
        DocsEntry("DROP [GLOBAL] FUNCTION", "${STATEMENTS}Function/DROP_FUNCTION/", "DROP FUNCTION"),
        // Clauses.
        DocsEntry("PIVOT", "${STATEMENTS}table_bucket_part_index/SELECT/SELECT_PIVOT/", "PIVOT", clause = true),
    ),
    tableFunctions = mapOf(
        "files" to "sql-reference/sql-functions/table-functions/files/",
        "generate_series" to "sql-reference/sql-functions/table-functions/generate_series/",
        "native_query" to "sql-reference/sql-functions/table-functions/native_query/",
    ),
)
