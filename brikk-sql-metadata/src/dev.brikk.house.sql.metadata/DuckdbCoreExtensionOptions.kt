package dev.brikk.house.sql.metadata

/** Handwritten vocabulary from the extension docs, not a runtime validator or
 * evidence that an extension is installed. Original option names/values are never rewritten.
 * Sources: DuckLake 1.1 connecting; DuckDB 2.0 Iceberg options and Delta documentation.
 */
val DUCKDB_CORE_EXTENSION_ATTACH_OPTIONS: Map<String,List<PropertyDef>> = mapOf(
    "ducklake" to listOf(
        PropertyDef("DATA_PATH",summary="Storage location; attaching a missing DuckLake can create a catalog."),
        PropertyDef("DUCKLAKE_VERSION",listOf("1.0","1.1"),"Requested catalog version; migration still requires explicit native policy."),
        PropertyDef("AUTOMATIC_MIGRATION",listOf("true","false"),"Native migration decision, not a parser action."),
        PropertyDef("METADATA_PARAMETERS",summary="MAP of parameters forwarded to the metadata catalog."),
        PropertyDef("SNAPSHOT_VERSION",summary="Snapshot id for a historical attachment."),
        PropertyDef("SNAPSHOT_TIME",summary="Historical attachment time; not the current catalog schema."),
    ),
    "iceberg" to listOf(
        PropertyDef("URI",summary="Canonical REST catalog endpoint option in 2.0."),
        PropertyDef("ENDPOINT",summary="Deprecated URI alias; supplying both is a native error. Preserve authored spelling."),
        PropertyDef("AUTHORIZATION_TYPE",listOf("OAUTH2","SIGV4","NONE")),
        PropertyDef("ENDPOINT_TYPE",listOf("S3_TABLES","GLUE"),"Incompatible with URI/ENDPOINT/AUTHORIZATION_TYPE; native validation only."),
        PropertyDef("EXTRA_HTTP_HEADERS",summary="MAP(VARCHAR,VARCHAR), potentially sensitive authored data."),
        PropertyDef("MAX_TABLE_STALENESS",summary="INTERVAL, without querying the endpoint during analysis."),
        PropertyDef("TABLE_RESOLUTION",listOf("lazy","eager"),"Schema enumeration policy; never promise full schemas for lazy placeholders."),
        PropertyDef("SUPPORT_NESTED_NAMESPACES",listOf("true","false"),"Does not add recursive IDE schema-model support."),
        PropertyDef("DEFAULT_TABLE_LOCATION_FROM_NAMESPACE",listOf("true","false")),
    ),
    "delta" to listOf(
        PropertyDef("VERSION",summary="UBIGINT historical version; mutually exclusive with TIMESTAMP."),
        PropertyDef("TIMESTAMP",summary="TIMESTAMP WITH TIME ZONE historical default; an AT clause can override it."),
        PropertyDef("PIN_SNAPSHOT",listOf("true","false")),
        PropertyDef("PUSHDOWN_FILTERS",listOf("none","all","constant_only","dynamic_only")),
    ),
)
