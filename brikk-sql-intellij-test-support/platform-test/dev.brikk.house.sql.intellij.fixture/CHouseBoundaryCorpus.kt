package dev.brikk.house.sql.intellij.fixture

/** One script and the statements core and the editor must find in it, in order, without trivia between them. */
data class BoundaryCase(val name: String, val statements: List<String>, val separator: String = "\n") {
    val sql: String = statements.joinToString(separator)
}

/** A real s3 export, sanitized: its layout and indentation are as written. */
val OWNER_S3_EXPORT: String = listOf(
    "INSERT INTO FUNCTION s3(",
    "        'https://objects.example.invalid/1a2b3c4d5e6f:bucket-name/archive/events/{_partition_id}/node-1-data.parquet',",
    "        'ACCESSKEYEXAMPLE', 'SECRETKEYEXAMPLE', 'Parquet'",
    "                     )",
    "    PARTITION BY concat(",
    "    'year=',  toString(toYear(toDateTime(inserted_at))), '/',",
    "    'month=', toString(toMonth(toDateTime(inserted_at))), '/',",
    "    'day=',   toString(toDayOfMonth(toDateTime(inserted_at)))",
    "    )",
    "SELECT *",
    "FROM db.events_export_view",
    "WHERE (event_ts >= toDate('2026-01-01')",
    "    AND event_ts < toDate('2026-01-02'))",
    "    SETTINGS",
    "    max_memory_usage = 512000000000,",
    "    max_threads = 4,",
    "    s3_truncate_on_insert = 0,",
    "    output_format_parquet_compression_method = 'zstd',",
    "    output_format_parquet_parallel_encoding = 1;",
).joinToString("\n")

/**
 * ClickHouse statements whose boundaries the stock grammar or lexer gets wrong, with their
 * neighbours: INSERT INTO [TABLE] FUNCTION with PARTITION BY (which the stock grammar lacks, so
 * a SELECT on a new line starts a statement), and raw FORMAT data, framed as clickhouse-client and
 * the platform editor frame it (docs/brikk-extensions.md #55).
 */
val CHOUSE_BOUNDARY_CORPUS: List<BoundaryCase> = listOf(
    BoundaryCase("owner s3 export", listOf(OWNER_S3_EXPORT)),
    BoundaryCase("owner s3 export, then a select", listOf(OWNER_S3_EXPORT, "SELECT 1;")),
    BoundaryCase("owner s3 export as a table function", listOf(OWNER_S3_EXPORT.replaceFirst("INTO FUNCTION", "INTO TABLE FUNCTION"))),
    BoundaryCase("owner s3 export without settings", listOf(OWNER_S3_EXPORT.substringBefore("\n    SETTINGS") + ";", "SELECT 2;")),
    BoundaryCase("owner s3 export, unterminated", listOf(OWNER_S3_EXPORT.removeSuffix(";"))),
    BoundaryCase(
        "partitioned insert with comments",
        listOf(
            """
            INSERT INTO /* destination */ FUNCTION
                s3('https://example.invalid/log/{_partition_id}/data.parquet', 'Parquet')
            -- partition expression
            PARTITION BY toYYYYMM(ts)
            /* SELECT must stay in the INSERT */ SELECT * FROM logs
            SETTINGS max_threads = 2;
            """.trimIndent(),
            "SELECT 3;",
        ),
    ),
    BoundaryCase(
        "partitioned file and remote functions",
        listOf(
            "INSERT INTO TABLE FUNCTION file('out/{_partition_id}.csv', 'CSV', 'id UInt64')\nPARTITION BY id % 4\nSELECT number FROM numbers(8);",
            "INSERT INTO FUNCTION remote('example.invalid', 'db', 'events')\nSELECT * FROM events;",
            "INSERT INTO FUNCTION null('id UInt64') SELECT id FROM events;",
        ),
    ),
    BoundaryCase(
        "partitioned insert with a format tail",
        listOf(
            "INSERT INTO FUNCTION s3('https://example.invalid/{_partition_id}.parquet', 'Parquet')\n    PARTITION BY toYYYYMM(ts)\nSELECT * FROM logs\nFORMAT Parquet;",
            "SELECT 4;",
        ),
    ),
    BoundaryCase("json data tail", listOf("INSERT INTO events FORMAT JSONEachRow\n{\"id\":1,\"label\":\"a; b\"}\n{\"id\":2,\"label\":\"c\"}\n;")),
    // A `;` ending a data line is data, also at the end of the text.
    BoundaryCase("json ending in a data semicolon", listOf("INSERT INTO events FORMAT JSONEachRow\n{\"id\":1};")),
    BoundaryCase("json then a semicolon line", listOf("INSERT INTO events FORMAT JSONEachRow\n{\"id\":1,\"q\":\"it's\"}\n;", "SELECT 2;")),
    BoundaryCase("json then a blank line", listOf("INSERT INTO events FORMAT JSONEachRow\n{\"id\":1}", "SELECT 2;"), "\n\n"),
    // As TSV and CSV store it: a `;` ending a data line is data.
    BoundaryCase("json swallowing a select", listOf("INSERT INTO events FORMAT JSONEachRow\n{\"id\":1};\nSELECT 2;")),
    BoundaryCase("json on one line swallowing a select", listOf("INSERT INTO events FORMAT JSONEachRow {\"id\":1};\nSELECT 2;")),
    BoundaryCase("tsv on one line then a semicolon line", listOf("INSERT INTO events FORMAT TSV a;b\n ;", "SELECT 2;")),
    BoundaryCase("tsv with semicolons and quotes", listOf("INSERT INTO events FORMAT TSV\na;b\tO'Brien\n;", "SELECT 2;")),
    BoundaryCase("csv with quotes then a blank line", listOf("INSERT INTO events FORMAT CSV\n1,O'Brien\n2,\"x;y\"", "SELECT 2;"), "\n\n"),
    BoundaryCase("consecutive inserts ended by blank lines",
        listOf("INSERT INTO events FORMAT TSV\na", "INSERT INTO events FORMAT TSV\nb;c", "SELECT 2;"), "\n\n"),
    BoundaryCase("values followed by select", listOf("INSERT INTO events FORMAT Values (1, 'x;y');", "SELECT 2;")),
    BoundaryCase("settings and format", listOf("SELECT number FROM numbers(5) SETTINGS max_threads = 2 FORMAT JSONEachRow;", "SELECT 1 AS value FORMAT TSV;")),
    BoundaryCase("semicolons in trivia", listOf("SELECT 'a; b' /* ; SELECT 9 */ AS value -- ; SELECT 8\nFROM events;", "SELECT /* ; */ 2;")),
)
