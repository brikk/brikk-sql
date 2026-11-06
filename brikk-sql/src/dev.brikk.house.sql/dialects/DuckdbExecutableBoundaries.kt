package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*

private val FRACTIONAL_INTERVAL = Regex("^[+-]?(?:[0-9]+\\.[0-9]*|\\.[0-9]+|[0-9]+[eE][+-]?[0-9]+)$")

/** Reviewed 2.0 gaps. Rendering a familiar name is not a proof of executable semantics. */
internal fun Expression.duckdbExecutableBoundaryIssue(source: String?, target: String, strict: Boolean): String? {
    val from = source?.lowercase() ?: return null
    if (from != "duckdb" && target != "duckdb") return null
    val nodes = walk().toList()
    if (from != target && nodes.any { it.reviewedDuckdbFunctionName() != null })
        return "Reviewed DuckDB function result typing is not a verified cross-target execution mapping"
    if (from != target && nodes.any { it is DateDiff || it is TimestampDiff || it is TsOrDsDiff || it is DateTrunc || it is TimestampTrunc })
        return "Temporal boundary-counting and truncation result types have no verified executable DuckDB cross-target mapping"
    if (from != target && nodes.any {
            // Preserve the pre-existing Trino global-replace preview contract. It
            // does not qualify arbitrary regex patterns for strict execution.
            val globalPreview = !strict && from == "duckdb" && target == "trino" && it is RegexpReplace &&
                (it.args["modifiers"] as? Literal)?.let { flag -> flag.isString && flag.name == "g" } == true
            !globalPreview && (it.duckdbRegexArguments()?.size == 4 ||
                it is RegexpLike && it.args["flag"] != null || it is RegexpFullMatch && it.args["options"] != null)
        })
        return "DuckDB regex option flags have no verified cross-target execution contract"
    if (target == "duckdb") {
        if (nodes.any { it is Interval && it.args["unit"] != null && (it.thisArg as? Literal)?.name?.let { value ->
                FRACTIONAL_INTERVAL.matches(value.trim().substringBefore(' '))
            } == true }) return "Fractional interval string/unit preservation is not qualified for executable DuckDB generation"
        if (nodes.any { it is JSONExtract && it.expressionsArg.isNotEmpty() })
            return "Multi-path JSON extraction must not silently lose paths; executable DuckDB mapping is unqualified"
        if (nodes.any { (it is JSONArray || it is JSONObject) && listOf("null_handling", "return_type", "encoding", "strict", "unique_keys").any { key -> it.args[key] != null } })
            return "SQL/JSON constructor options have no lossless executable DuckDB mapping"
    }
    if (from == "duckdb" && target != "duckdb") {
        if (this is Analyze) return "DuckDB statistics statements have no verified cross-target execution contract"
        if (nodes.any { it is Merge }) return "DuckDB MERGE matching/actions/RETURNING have no verified cross-target execution contract"
        if (this is dev.brikk.house.sql.ast.Set || this is Pragma || this is Attach || this is Command)
            return "DuckDB session/storage/raw commands have no verified cross-target execution contract"
        if (nodes.any { it is Table && it.parts.size>3 }) return "DuckDB nested namespace paths have no verified cross-target mapping"
        if (this is Create && text("kind").equals("TABLE", true) && nodes.filterIsInstance<Select>().any { select ->
                select.expressionsArg.any { projection ->
                    var value = projection
                    while (value is Alias || value is Paren) value = value.thisArg as? Expression ?: break
                    value is Null
                }
            }) return "DuckDB NULL-typed CTAS columns have no verified target storage-type mapping"
    }
    if (target == "duckdb" && from != "duckdb") {
        if (nodes.any { it is UnicodeString }) return "Unicode escape-string conversion to DuckDB is not qualified"
        if (nodes.any { it is ArrayInsert || it is ArraySlice || it is ArraySort || it is SortArray })
            return "Array index/endpoint/comparator/null-order semantics have no verified executable DuckDB mapping"
        if (nodes.any { it is ArrayToString && it.args["null"] != null }) return "ARRAY_TO_STRING replacement coercion is not qualified for DuckDB"
        if (nodes.any { (it is Base64Encode || it is Base64DecodeString || it is Base64DecodeBinary) && it.args["alphabet"] != null })
            return "Custom Base64 alphabet translation is not qualified for executable DuckDB generation"
    }
    return null
}
