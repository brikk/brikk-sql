package dev.brikk.house.sql.metadata

/** ClickHouse table functions whose output columns do not depend on their arguments (ClickHouse documentation). */
val CLICKHOUSE_TABLE_FUNCTIONS: List<TableFunctionInfo> = listOf(
    TableFunctionInfo("numbers", schema = OutputSchema.Fixed(listOf(ColumnDef("number", "UInt64")))),
    TableFunctionInfo("numbers_mt", schema = OutputSchema.Fixed(listOf(ColumnDef("number", "UInt64")))),
    TableFunctionInfo("zeros", schema = OutputSchema.Fixed(listOf(ColumnDef("zero", "UInt8")))),
    TableFunctionInfo("zeros_mt", schema = OutputSchema.Fixed(listOf(ColumnDef("zero", "UInt8")))),
    TableFunctionInfo("generate_series", schema = OutputSchema.Fixed(listOf(ColumnDef("generate_series", "UInt64")))),
)

val CLICKHOUSE_METADATA: DialectMetadata = singleSnapshot(
    "clickhouse",
    MetadataSnapshot("26.5.1.1", CLICKHOUSE_FUNCTION_CATALOG, CLICKHOUSE_26_5_1_1_KEYWORDS, tableFunctions = CLICKHOUSE_TABLE_FUNCTIONS),
    CLICKHOUSE_DOCS,
)
