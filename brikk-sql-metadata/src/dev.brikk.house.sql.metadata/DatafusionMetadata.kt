package dev.brikk.house.sql.metadata

/** The explicitly captured upstream embedding, not every DataFusion application's registry. */
val DATAFUSION_METADATA: DialectMetadata = singleSnapshot(
    "datafusion", datafusionSnapshot(), docs = EngineDocs(
        dialect = "datafusion", version = "current", release = "55.2.0",
        base = "https://datafusion.apache.org/user-guide/sql/",
        statements = listOf(
            DocsEntry("SELECT|WITH", "select.html", "Query syntax"),
            DocsEntry("EXPLAIN", "explain.html", "EXPLAIN"),
            DocsEntry("SHOW FUNCTIONS|TABLES|COLUMNS|ALL", "information_schema.html", "Information schema"),
            DocsEntry("PREPARE|EXECUTE", "prepared_statements.html", "Prepared statements"),
        ),
    ),
)
