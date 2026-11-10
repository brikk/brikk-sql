package dev.brikk.house.sql.ast

/**
 * Brikk-native [Expression.meta] keys. Values are part of the serialized form;
 * only the start-position anchors are omitted by [Serde].
 */
internal object BrikkMetaKeys {
    // Shared parser/generator markers and the legacy original-name fallback.
    const val NATIVE_ONLY_DIALECT = "native_only_dialect"
    const val NAME = "name"
    const val EXPLICIT_NULL_ORDERING = "explicit_null_ordering"

    // Parser start-position anchors, also copied by Expression.updatePositions.
    const val LINE_START = "line_start"
    const val COL_START = "col_start"

    // StarRocks syntax retained for native generation and analysis.
    const val STARROCKS_OPAQUE_COMMAND = "starrocks_opaque_command"
    const val STARROCKS_NATIVE_PIVOT = "starrocks_native_pivot"
    const val STARROCKS_MAP_LITERAL = "starrocks_map_literal"
    const val STARROCKS_NATIVE_DDL = "starrocks_native_ddl"
    const val STARROCKS_TO_DAYS = "starrocks_to_days"

    // ClickHouse raw JSON extraction contract.
    const val CLICKHOUSE_JSON_EXTRACT_RAW = "clickhouse_json_extract_raw"

    // DataFusion syntax, parameter spelling and Arrow analysis types.
    const val NUMBERED_DOLLAR_PARAMETER = "numbered_dollar_parameter"
    const val OPERATOR = "operator"
    const val DATAFUSION_IMPLICIT_OPTION = "datafusion_implicit_option"
    const val DATAFUSION_CLUSTER_EXPRESSIONS = "datafusion_cluster_expressions"
    const val DATAFUSION_NATIVE_CALL = "datafusion_native_call"
    const val DATAFUSION_FUNCTION_NAME = "datafusion_function_name"
    const val DATAFUSION_TYPE_NAME = "datafusion_type_name"
    const val DATAFUSION_DATE_BIN = "datafusion_date_bin"
    const val DATAFUSION_OMITTED_SERIES_START = "datafusion_omitted_series_start"
    const val DATAFUSION_CAST_STYLE = "datafusion_cast_style"
    const val DATAFUSION_ARROW_TYPE = "datafusion_arrow_type"

    // Spice operators, options and relation-call arguments.
    const val SPICE_EXPONENT_OPERATOR = "spice_exponent_operator"
    const val SPICE_RELATION_CALL_ARGUMENT = "spice_relation_call_argument"
    const val SPICE_IMPLICIT_OPTION = "spice_implicit_option"

    // DuckDB pagination, COPY ordering and native weekday semantics.
    const val DUCKDB_PAGINATION_POSITION = "duckdb_pagination_position"
    const val DUCKDB_COPY_DEFAULT_NULL_ORDER = "duckdb_copy_default_null_order"
    const val DUCKDB_NATIVE_WEEKDAY = "duckdb_native_weekday"

    // Doris full-text predicates.
    const val DORIS_FULL_TEXT = "doris_full_text"
}
