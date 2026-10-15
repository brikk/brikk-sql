package dev.brikk.house.sql.docs

import dev.brikk.house.sql.metadata.CLICKHOUSE_METADATA
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.STARROCKS_METADATA
import dev.brikk.house.sql.metadata.TRINO_METADATA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal val METADATA = listOf(DORIS_METADATA, CLICKHOUSE_METADATA, DUCKDB_METADATA, STARROCKS_METADATA, TRINO_METADATA).associateBy { it.dialect }

class SqlDocsTest {
    @Test
    fun docsHangOffEachDialectsMetadata() {
        for (metadata in listOf(DORIS_METADATA, CLICKHOUSE_METADATA, DUCKDB_METADATA, STARROCKS_METADATA)) {
            assertEquals(metadata.dialect, metadata.docs?.dialect)
        }
        assertNull(TRINO_METADATA.docs)
    }

    /** The link at `<caret>` in [sql]. */
    private fun at(sql: String, dialect: String = "doris") =
        METADATA[dialect]?.docs?.let { sqlDocsAt(sql.replace("<caret>", ""), it, sql.indexOf("<caret>")) }

    private val doris = "https://doris.apache.org/docs/4.x/sql-manual/"

    @Test
    fun dorisStatementFormsLinkTheirPinnedPages() {
        for ((sql, page) in listOf(
            "CREATE <caret>JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO t SELECT 1" to "sql-statements/job/CREATE-JOB/",
            "<caret>create external table t (k INT) ENGINE=hive" to "sql-statements/table-and-view/table/CREATE-TABLE/",
            "CREATE CATALOG<caret> hive PROPERTIES ('type' = 'hms')" to "sql-statements/catalog/CREATE-CATALOG/",
            "INSERT <caret>INTO t SELECT 1" to "sql-statements/data-modification/DML/INSERT/",
            "INSERT <caret>OVERWRITE TABLE t SELECT 1" to "sql-statements/data-modification/DML/INSERT-OVERWRITE/",
            "<caret>EXPORT TABLE t TO 's3://b/p/'" to "sql-statements/data-modification/load-and-export/EXPORT/",
            "SELECT k FROM t INTO <caret>OUTFILE 's3://b/p/'" to "sql-statements/data-modification/load-and-export/OUTFILE/",
            "CREATE ROUTINE <caret>LOAD db.job ON t FROM KAFKA ('kafka_topic' = 'x')" to "sql-statements/data-modification/load-and-export/CREATE-ROUTINE-LOAD/",
            "<caret>SET exec_mem_limit = 1" to "sql-statements/session/variable/SET-VARIABLE/",
            "SET <caret>PASSWORD = PASSWORD('x')" to "sql-statements/account-management/SET-PASSWORD/",
            "/* lead */ CREATE OR REPLACE <caret>VIEW v AS SELECT 1" to "sql-statements/table-and-view/view/CREATE-VIEW/",
            "SELECT 1; REFRESH <caret>CATALOG hive" to "sql-statements/catalog/REFRESH/",
        )) assertEquals(doris + page, at(sql)?.url, sql)
        val job = at("CREATE <caret>JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO t SELECT 1")
        assertEquals("CREATE JOB" to (0 until 10), job?.title to job?.let { it.start until it.end })
    }

    @Test
    fun dorisTableFunctionsLinkTheirPagesOnlyInRelationPositions() {
        assertEquals("${doris}sql-functions/table-valued-functions/s3/", at("SELECT * FROM <caret>s3(\"uri\" = \"s3://b/p\", \"format\" = \"csv\")")?.url)
        assertEquals("${doris}sql-functions/table-valued-functions/partition-values/", at("SELECT * FROM partition_<caret>values(\"catalog\" = \"c\")")?.url)
        assertEquals("${doris}sql-functions/table-valued-functions/s3/", at("INSERT INTO s3<caret>(\"uri\" = \"s3://b/p/\") SELECT 1")?.url)
        // A column, or a function of that name outside FROM, is no table function.
        assertNull(at("SELECT <caret>numbers FROM t"))
        assertNull(at("SELECT local<caret>(1) FROM t"))
    }

    private val clickhouse = "https://clickhouse.com/docs/"

    @Test
    fun clickhouseStatementsClausesEnginesAndTableFunctionsLinkTheirPages() {
        for ((sql, page) in listOf(
            "CREATE <caret>TABLE t (x UInt8) ENGINE = MergeTree ORDER BY x" to "reference/statements/create/table",
            "CREATE TABLE t (x UInt8) ENGINE = <caret>ReplacingMergeTree ORDER BY x" to "reference/engines/table-engines/mergetree-family/replacingmergetree",
            "CREATE TABLE t (x UInt8) ENGINE = ReplicatedMergeTree<caret>('/p', 'r') ORDER BY x" to "reference/engines/table-engines/mergetree-family/replication",
            "CREATE TABLE t (x UInt8) ENGINE Memory<caret>" to "reference/engines/table-engines/special/memory",
            "CREATE MATERIALIZED <caret>VIEW v TO t AS SELECT 1" to "reference/statements/create/view",
            "INSERT INTO <caret>FUNCTION s3('https://b/p.csv', 'CSV') SELECT 1" to "reference/statements/insert-into",
            "INSERT INTO FUNCTION <caret>s3('https://b/p.csv', 'CSV') SELECT 1" to "reference/functions/table-functions/s3",
            "SELECT 1 <caret>SETTINGS max_threads = 1" to "concepts/features/configuration/settings/settings-query-level",
            "SELECT 1 FORMAT<caret> JSONEachRow" to "reference/statements/select/format",
            "SELECT * FROM <caret>generateRandom('a UInt8')" to "reference/functions/table-functions/generate",
            "SELECT * FROM icebergS3<caret>('https://b/t')" to "reference/functions/table-functions/iceberg",
            "SELECT * FROM t CROSS JOIN <caret>numbers(3) AS n" to "reference/functions/table-functions/numbers",
            "<caret>OPTIMIZE TABLE t FINAL" to "reference/statements/optimize",
        )) assertEquals(clickhouse + page, at(sql, "clickhouse")?.url, sql)
        assertNull(at("SELECT <caret>MergeTree FROM t", "clickhouse"))
        assertNull(at("SELECT * FROM <caret>viewExplain('EXPLAIN', '', (SELECT 1))", "clickhouse"))
    }

    private val duckdb = "https://duckdb.org/docs/current/"

    @Test
    fun duckdbStatementsClausesAndTableFunctionsLinkTheirPages() {
        for ((sql, page) in listOf(
            "<caret>ATTACH 'file.db' AS f" to "sql/statements/attach",
            "<caret>ATTACH 'ducklake:metadata.ducklake' AS lake (DATA_PATH 'files/')" to "core_extensions/ducklake",
            "ATTACH <caret>DATABASE 'x.db' AS x" to "sql/statements/attach",
            "<caret>COPY t TO 'out.parquet' (FORMAT parquet)" to "sql/statements/copy",
            "CREATE OR REPLACE TEMP <caret>MACRO add_one(x) AS x + 1" to "sql/statements/create_macro",
            "<caret>PIVOT t ON k USING sum(v)" to "sql/statements/pivot",
            "SELECT * FROM t <caret>UNPIVOT (v FOR k IN (a, b))" to "sql/statements/unpivot",
            "SET <caret>VARIABLE x = 1" to "sql/statements/set_variable",
            "<caret>SET threads = 4" to "sql/statements/set",
            "UPDATE <caret>EXTENSIONS" to "sql/statements/update_extensions",
            "SELECT * FROM <caret>read_parquet('data/*.parquet')" to "data/parquet/overview",
            "SELECT * FROM t JOIN <caret>read_csv('x.csv') AS c ON true" to "data/csv/overview",
            "SELECT * FROM duckdb_<caret>tables()" to "sql/meta/duckdb_table_functions",
        )) assertEquals(duckdb + page, at(sql, "duckdb")?.url, sql)
        assertNull(at("SELECT <caret>read_parquet FROM t", "duckdb"))
    }

    private val starrocks = "https://docs.starrocks.io/docs/"

    @Test
    fun starrocksStatementsAndTableFunctionsLinkTheirPages() {
        for ((sql, page) in listOf(
            "CREATE <caret>TABLE t (k INT) DUPLICATE KEY(k) DISTRIBUTED BY HASH(k)" to "sql-statements/table_bucket_part_index/CREATE_TABLE/",
            "CREATE MATERIALIZED <caret>VIEW mv REFRESH ASYNC AS SELECT 1" to "sql-statements/materialized_view/CREATE_MATERIALIZED_VIEW/",
            "<caret>SUBMIT TASK t AS INSERT INTO t SELECT 1" to "sql-statements/loading_unloading/ETL/SUBMIT_TASK/",
            "CREATE <caret>PIPE p AS INSERT INTO t SELECT * FROM FILES('path' = 's3://b/p/')" to "sql-statements/loading_unloading/pipe/CREATE_PIPE/",
            "SET <caret>CATALOG hive" to "sql-statements/Catalog/SET_CATALOG/",
            "CREATE PIPE p AS INSERT INTO t SELECT * FROM <caret>FILES('path' = 's3://b/p/')" to "sql-functions/table-functions/files/",
        )) assertEquals(starrocks + "sql-reference/" + page, at(sql, "starrocks")?.url, sql)
    }

    @Test
    fun nothingIsOfferedForUnrelatedTokensOrUnknownDialects() {
        for (sql in listOf("SELECT k FROM <caret>t", "SELECT 'CREATE <caret>JOB'", "SELECT 1 -- CREATE <caret>JOB", "CREATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO <caret>t SELECT 1",
            "SELECT k <caret>FROM t", "SELECT `create` FROM t WHERE k = <caret>1")) assertNull(at(sql), sql)
        assertNull(at("CREATE <caret>JOB j", "trino"))
    }
}
