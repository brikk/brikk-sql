package dev.brikk.house.sql.metadata

/** ClickHouse table functions whose output columns do not depend on their arguments (ClickHouse documentation). */
val CLICKHOUSE_TABLE_FUNCTIONS: List<TableFunctionInfo> = listOf(
    TableFunctionInfo("numbers", schema = OutputSchema.Fixed(listOf(ColumnDef("number", "UInt64")))),
    TableFunctionInfo("numbers_mt", schema = OutputSchema.Fixed(listOf(ColumnDef("number", "UInt64")))),
    TableFunctionInfo("zeros", schema = OutputSchema.Fixed(listOf(ColumnDef("zero", "UInt8")))),
    TableFunctionInfo("zeros_mt", schema = OutputSchema.Fixed(listOf(ColumnDef("zero", "UInt8")))),
    TableFunctionInfo("generate_series", schema = OutputSchema.Fixed(listOf(ColumnDef("generate_series", "UInt64")))),
)

val CLICKHOUSE_METADATA: DialectMetadata = DialectMetadata(
    "clickhouse",
    listOf(
        MetadataSnapshot("26.5.1.1", CLICKHOUSE_26_5_1_1_FUNCTION_CATALOG, CLICKHOUSE_26_5_1_1_KEYWORDS,
            tableFunctions = CLICKHOUSE_TABLE_FUNCTIONS),
        MetadataSnapshot("26.8.34.13", CLICKHOUSE_26_8_34_13_FUNCTION_CATALOG, CLICKHOUSE_26_8_34_13_KEYWORDS,
            tableFunctions = CLICKHOUSE_TABLE_FUNCTIONS, settings = CLICKHOUSE_26_8_34_13_SETTINGS,
            types = CLICKHOUSE_26_8_34_13_TYPES),
        MetadataSnapshot("26.10.2.103", CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG, CLICKHOUSE_26_10_2_103_KEYWORDS,
            tableFunctions = CLICKHOUSE_TABLE_FUNCTIONS, settings = CLICKHOUSE_26_10_2_103_SETTINGS,
            types = CLICKHOUSE_26_10_2_103_TYPES,
            grammarForms = listOf(grammarForm("TRY_CAST", InvocationSyntax.SPECIAL_FORM,
                template = "TRY_CAST(expression AS type)"))),
    ),
    defaultVersion = "26.10.2.103",
    docs = CLICKHOUSE_DOCS,
)
