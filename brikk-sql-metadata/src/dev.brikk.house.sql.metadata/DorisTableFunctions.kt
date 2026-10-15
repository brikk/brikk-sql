package dev.brikk.house.sql.metadata

/*
 * HANDWRITTEN (not generated): Doris table-valued function properties and output schemas.
 * No engine table exposes them, so they are curated, and need a refresh per Doris release.
 *
 * Sources, checked 2026-10-11:
 *  - doris-intellij-plugin `src/main/kotlin/dev/sort/doris/sql/DorisTableFunctions.kt` and
 *    `RESEARCH-tvf-completion.md` at 2913ad276d49c46fabe178ebb8155e3b4286c866: every entry
 *    except those below. The research read the apache/doris-website master docs
 *    (`docs/sql-manual/sql-functions/table-valued-functions/`) on 2026-07-07 without
 *    recording a commit; master was then at 0a22aeec9089be5a925327faa68dc339dc50a542.
 *  - apache/doris tag 4.1.4 (ad35a140c7fd), fe/fe-core: `VectorSearchTableValuedFunction`
 *    (vector_search), `ParquetMetadataTableValuedFunction` (parquet_*), `FileTableValuedFunction`
 *    (file), and `BuiltinTableValuedFunctions` / `TableValuedFunctionIf` for which names exist.
 *
 * `iceberg_meta` (documented for earlier 4.x releases) is not registered at 4.1.4, so it is
 * not listed; its schemas are in the plugin file above.
 * Column types use plain spellings: text for strings and nested types.
 */

/** Keys shared by the file-reading table functions (s3, hdfs, local, http, file). */
private val FILE_PARSING_PROPERTIES = listOf(
    PropertyDef("format", listOf("csv", "csv_with_names", "csv_with_names_and_types", "json", "parquet", "orc", "avro")),
    PropertyDef("column_separator"),
    PropertyDef("line_delimiter"),
    PropertyDef("compress_type"),
    PropertyDef("read_json_by_line", BOOLEAN_VALUES),
    PropertyDef("strip_outer_array", BOOLEAN_VALUES),
    PropertyDef("json_root"),
    PropertyDef("jsonpaths"),
    PropertyDef("num_as_string", BOOLEAN_VALUES),
    PropertyDef("fuzzy_parse", BOOLEAN_VALUES),
    PropertyDef("trim_double_quotes", BOOLEAN_VALUES),
    PropertyDef("skip_lines"),
    PropertyDef("path_partition_keys"),
)

private val BOOLEAN_VALUES: List<String> get() = listOf("true", "false")

private fun columns(vararg pairs: Pair<String, String>) = pairs.map { (name, type) -> ColumnDef(name, type) }

private val PARQUET_LOCATION = listOf(PropertyDef("uri"), PropertyDef("file_path"))

private val PARQUET_METADATA_COLUMNS = columns(
    "file_name" to "text", "row_group_id" to "bigint", "row_group_num_rows" to "bigint",
    "row_group_num_columns" to "bigint", "row_group_bytes" to "bigint", "column_id" to "bigint",
    "file_offset" to "bigint", "num_values" to "bigint", "path_in_schema" to "text", "type" to "text",
    "stats_min" to "text", "stats_max" to "text", "stats_null_count" to "bigint",
    "stats_distinct_count" to "bigint", "stats_min_value" to "text", "stats_max_value" to "text",
    "compression" to "text", "encodings" to "text", "index_page_offset" to "bigint",
    "dictionary_page_offset" to "bigint", "data_page_offset" to "bigint",
    "total_compressed_size" to "bigint", "total_uncompressed_size" to "bigint",
    "key_value_metadata" to "text", "bloom_filter_offset" to "bigint", "bloom_filter_length" to "bigint",
    "min_is_exact" to "boolean", "max_is_exact" to "boolean", "row_group_compressed_bytes" to "bigint",
)

private val PARQUET_SCHEMA_COLUMNS = columns(
    "file_name" to "text", "name" to "text", "type" to "text", "type_length" to "bigint",
    "repetition_type" to "text", "num_children" to "bigint", "converted_type" to "text",
    "scale" to "bigint", "precision" to "bigint", "field_id" to "bigint", "logical_type" to "text",
)

private val PARQUET_FILE_METADATA_COLUMNS = columns(
    "file_name" to "text", "created_by" to "text", "num_rows" to "bigint", "num_row_groups" to "bigint",
    "format_version" to "bigint", "encryption_algorithm" to "text", "footer_signing_key_metadata" to "text",
)

private val PARQUET_KV_METADATA_COLUMNS = columns("file_name" to "text", "key" to "text", "value" to "text")

private val PARQUET_BLOOM_PROBE_COLUMNS = columns(
    "file_name" to "text", "row_group_id" to "int", "bloom_filter_excludes" to "int",
)

/**
 * [function] with its keys' summaries and since from the 4.x docs and its own FE code
 * (GeneratedDorisProperties.kt), and the keys its page documents that the list above lacks.
 */
private fun documented(function: TableFunctionInfo): TableFunctionInfo {
    val page = DORIS_TABLE_FUNCTION_KEYS[function.name]?.second.orEmpty()
    val sources = DORIS_TABLE_FUNCTION_KEY_SINCE[function.name].orEmpty()
    fun PropertyDef.annotated() = copy(summary = summary ?: page[name]?.first, since = since ?: page[name]?.second ?: sources[name])
    val listed = function.properties.map { it.annotated() }
    val documentedOnly = if (function.properties.isEmpty()) emptyList()
    else page.filterKeys { key -> listed.none { it.name == key } }.map { (key, info) -> PropertyDef(key, emptyList(), info.first, info.second) }
    return function.copy(properties = listed + documentedOnly)
}

private val HANDWRITTEN_TABLE_FUNCTIONS: List<TableFunctionInfo> = listOf(
    TableFunctionInfo("backends", schema = OutputSchema.Fixed(columns(
        "BackendId" to "bigint", "Host" to "text", "HeartbeatPort" to "int", "BePort" to "int",
        "HttpPort" to "int", "BrpcPort" to "int", "ArrowFlightSqlPort" to "int",
        "LastStartTime" to "datetime", "LastHeartbeat" to "datetime", "Alive" to "boolean",
        "SystemDecommissioned" to "boolean", "TabletNum" to "bigint", "DataUsedCapacity" to "text",
        "TrashUsedCapacity" to "text", "AvailCapacity" to "text", "TotalCapacity" to "text",
        "UsedPct" to "text", "MaxDiskUsedPct" to "text", "RemoteUsedCapacity" to "text", "Tag" to "text",
        "ErrMsg" to "text", "Version" to "text", "Status" to "text", "HeartbeatFailureCounter" to "int",
        "NodeRole" to "text", "CpuCores" to "int", "Memory" to "text",
    ))),
    TableFunctionInfo("frontends", schema = OutputSchema.Fixed(columns(
        "Name" to "text", "Host" to "text", "EditLogPort" to "text", "HttpPort" to "text",
        "QueryPort" to "text", "RpcPort" to "text", "ArrowFlightSqlPort" to "text", "Role" to "text",
        "IsMaster" to "text", "ClusterId" to "text", "Join" to "text", "Alive" to "text",
        "ReplayedJournalId" to "text", "LastStartTime" to "text", "LastHeartbeat" to "text",
        "IsHelper" to "text", "ErrMsg" to "text", "Version" to "text", "CurrentConnected" to "text",
    ))),
    TableFunctionInfo("frontends_disks", schema = OutputSchema.Fixed(columns(
        "Name" to "text", "Host" to "text", "DirType" to "text", "Dir" to "text", "Filesystem" to "text",
        "Capacity" to "text", "Used" to "text", "Available" to "text", "UseRate" to "text", "MountOn" to "text",
    ))),
    TableFunctionInfo("catalogs", schema = OutputSchema.Fixed(columns(
        "CatalogId" to "bigint", "CatalogName" to "text", "CatalogType" to "text", "Property" to "text",
        "Value" to "text",
    ))),
    TableFunctionInfo("mv_infos", listOf(PropertyDef("database")), OutputSchema.Fixed(columns(
        "Id" to "bigint", "Name" to "text", "JobName" to "text", "State" to "text",
        "SchemaChangeDetail" to "text", "RefreshState" to "text", "RefreshInfo" to "text",
        "QuerySql" to "text", "MvProperties" to "text", "MvPartitionInfo" to "text",
        "SyncWithBaseTables" to "boolean",
    ))),
    // The internal-table shape; the docs note external-catalog tables may differ slightly.
    TableFunctionInfo(
        "partitions",
        listOf(PropertyDef("catalog"), PropertyDef("database"), PropertyDef("table")),
        OutputSchema.Fixed(columns(
            "PartitionId" to "bigint", "PartitionName" to "text", "VisibleVersion" to "bigint",
            "VisibleVersionTime" to "datetime", "State" to "text", "PartitionKey" to "text", "Range" to "text",
            "DistributionKey" to "text", "Buckets" to "int", "ReplicationNum" to "int",
            "StorageMedium" to "text", "CooldownTime" to "datetime", "RemoteStoragePolicy" to "text",
            "LastConsistencyCheckTime" to "datetime", "DataSize" to "text", "IsInMemory" to "boolean",
            "ReplicaAllocation" to "text", "IsMutable" to "boolean", "SyncWithBaseTables" to "boolean",
            "UnsyncTables" to "text",
        )),
    ),
    TableFunctionInfo(
        "numbers",
        listOf(PropertyDef("number"), PropertyDef("const_value")),
        OutputSchema.Fixed(columns("number" to "bigint")),
    ),
    TableFunctionInfo("jobs", listOf(PropertyDef("type", listOf("insert", "mv"))), OutputSchema.ByProperty("type", mapOf(
        "insert" to columns(
            "Id" to "bigint", "Name" to "text", "Definer" to "text", "ExecuteType" to "text",
            "RecurringStrategy" to "text", "Status" to "text", "ExecuteSql" to "text",
            "CreateTime" to "datetime", "SucceedTaskCount" to "bigint", "FailedTaskCount" to "bigint",
            "CanceledTaskCount" to "bigint", "Comment" to "text",
        ),
        "mv" to columns(
            "Id" to "bigint", "Name" to "text", "MvId" to "bigint", "MvName" to "text",
            "MvDatabaseId" to "bigint", "MvDatabaseName" to "text", "ExecuteType" to "text",
            "RecurringStrategy" to "text", "Status" to "text", "CreateTime" to "datetime",
        ),
    ))),
    TableFunctionInfo("tasks", listOf(PropertyDef("type", listOf("insert", "mv"))), OutputSchema.ByProperty("type", mapOf(
        "insert" to columns(
            "TaskId" to "bigint", "JobId" to "bigint", "JobName" to "text", "Label" to "text",
            "Status" to "text", "ErrorMsg" to "text", "CreateTime" to "datetime", "FinishTime" to "datetime",
            "TrackingUrl" to "text", "LoadStatistic" to "text", "User" to "text",
        ),
        "mv" to columns(
            "TaskId" to "bigint", "JobId" to "bigint", "JobName" to "text", "MvId" to "bigint",
            "MvName" to "text", "MvDatabaseId" to "bigint", "MvDatabaseName" to "text", "Status" to "text",
            "ErrorMsg" to "text", "CreateTime" to "datetime", "StartTime" to "datetime",
            "FinishTime" to "datetime", "DurationMs" to "bigint", "TaskContext" to "text",
            "RefreshMode" to "text", "NeedRefreshPartitions" to "text", "CompletedPartitions" to "text",
            "Progress" to "text", "LastQueryId" to "text",
        ),
    ))),
    TableFunctionInfo(
        "hudi_meta",
        listOf(PropertyDef("table"), PropertyDef("query_type", listOf("timeline"))),
        OutputSchema.Fixed(columns(
            "timestamp" to "text", "action" to "text", "file_name" to "text", "state" to "text",
            "state_transition_time" to "text",
        )),
    ),
    TableFunctionInfo(
        "s3",
        listOf(
            PropertyDef("uri"), PropertyDef("s3.access_key"), PropertyDef("s3.secret_key"),
            PropertyDef("s3.region"), PropertyDef("s3.endpoint"), PropertyDef("s3.session_token"),
            PropertyDef("use_path_style", BOOLEAN_VALUES), PropertyDef("force_parsing_by_standard_uri", BOOLEAN_VALUES),
            PropertyDef("resource"),
        ) + FILE_PARSING_PROPERTIES,
    ),
    TableFunctionInfo(
        "hdfs",
        listOf(
            PropertyDef("uri"), PropertyDef("fs.defaultFS"), PropertyDef("hadoop.username"),
            PropertyDef("hadoop.security.authentication"), PropertyDef("hadoop.kerberos.principal"),
            PropertyDef("hadoop.kerberos.keytab"), PropertyDef("resource"),
        ) + FILE_PARSING_PROPERTIES,
    ),
    TableFunctionInfo(
        "local",
        listOf(PropertyDef("file_path"), PropertyDef("backend_id"), PropertyDef("shared_storage", BOOLEAN_VALUES)) +
            FILE_PARSING_PROPERTIES,
    ),
    TableFunctionInfo("http", listOf(PropertyDef("uri")) + FILE_PARSING_PROPERTIES),
    // Delegates to s3, hdfs, local or http by the storage the location properties name.
    TableFunctionInfo("file", listOf(PropertyDef("uri"), PropertyDef("file_path")) + FILE_PARSING_PROPERTIES),
    TableFunctionInfo("query", listOf(PropertyDef("catalog"), PropertyDef("query"))),
    TableFunctionInfo("partition_values", listOf(PropertyDef("catalog"), PropertyDef("database"), PropertyDef("table"))),
    // Columns come from the referenced Lance table.
    TableFunctionInfo(
        "vector_search",
        listOf(
            PropertyDef("table"), PropertyDef("column"), PropertyDef("query_vector"), PropertyDef("top_k"),
            PropertyDef("offset"), PropertyDef("metric", listOf("l2", "cosine", "dot", "dot_product", "hamming")),
            PropertyDef("filter"), PropertyDef("nprobes"), PropertyDef("refine_factor"), PropertyDef("ef"),
            PropertyDef("use_index", BOOLEAN_VALUES),
        ),
    ),
    TableFunctionInfo(
        "parquet_meta",
        PARQUET_LOCATION + listOf(
            PropertyDef(
                "mode",
                listOf("parquet_metadata", "parquet_schema", "parquet_file_metadata", "parquet_kv_metadata", "parquet_bloom_probe"),
            ),
            PropertyDef("column"),
            PropertyDef("value"),
        ),
        OutputSchema.ByProperty(
            "mode",
            mapOf(
                "parquet_metadata" to PARQUET_METADATA_COLUMNS,
                "parquet_schema" to PARQUET_SCHEMA_COLUMNS,
                "parquet_file_metadata" to PARQUET_FILE_METADATA_COLUMNS,
                "parquet_kv_metadata" to PARQUET_KV_METADATA_COLUMNS,
                "parquet_bloom_probe" to PARQUET_BLOOM_PROBE_COLUMNS,
            ),
            default = "parquet_metadata",
        ),
    ),
    TableFunctionInfo("parquet_file_metadata", PARQUET_LOCATION, OutputSchema.Fixed(PARQUET_FILE_METADATA_COLUMNS)),
    TableFunctionInfo("parquet_kv_metadata", PARQUET_LOCATION, OutputSchema.Fixed(PARQUET_KV_METADATA_COLUMNS)),
    TableFunctionInfo(
        "parquet_bloom_probe",
        PARQUET_LOCATION + listOf(PropertyDef("column"), PropertyDef("value")),
        OutputSchema.Fixed(PARQUET_BLOOM_PROBE_COLUMNS),
    ),
    // Stream-load, ingest and change-stream functions: registered, not FROM relations.
    TableFunctionInfo("group_commit", queryable = false),
    TableFunctionInfo("http_stream", queryable = false),
    TableFunctionInfo("cdc_stream", queryable = false),
)

internal val DORIS_TABLE_FUNCTIONS: List<TableFunctionInfo> = HANDWRITTEN_TABLE_FUNCTIONS.map(::documented)
