package dev.brikk.house.sql.metadata

/** Fixed schemas from Spice 78a47787, runtime-datafusion-udfs/{flatten_json,json_tree}.rs. */
internal fun spiceTableFunctions(names: List<String>): List<TableFunctionInfo> = names.map { name ->
    val columns = when (name) {
        "flatten_json", "flatten_json_properties" -> listOf("path", "parent_path", "key", "value", "type").map { ColumnDef(it, "VARCHAR") }
        "json_tree" -> listOf("key", "value", "type", "atom", "id", "parent", "fullkey", "path")
            .map { ColumnDef(it, if (it in setOf("id", "parent")) "BIGINT" else "VARCHAR") }
        else -> null
    }
    TableFunctionInfo(name, schema = columns?.let { OutputSchema.Fixed(it) } ?: OutputSchema.Open)
}
