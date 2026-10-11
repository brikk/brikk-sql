package dev.brikk.house.sql.metadata

val CLICKHOUSE_METADATA: DialectMetadata =
    singleSnapshot("clickhouse", MetadataSnapshot("26.5.1.1", CLICKHOUSE_FUNCTION_CATALOG, CLICKHOUSE_26_5_1_1_KEYWORDS))
