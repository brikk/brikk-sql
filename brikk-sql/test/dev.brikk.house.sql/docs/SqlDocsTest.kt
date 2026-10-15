package dev.brikk.house.sql.docs

import dev.brikk.house.sql.metadata.CLICKHOUSE_METADATA
import dev.brikk.house.sql.metadata.DocsEntry
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.STARROCKS_METADATA
import dev.brikk.house.sql.metadata.TRINO_METADATA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal val METADATA = listOf(DORIS_METADATA, CLICKHOUSE_METADATA, DUCKDB_METADATA, STARROCKS_METADATA, TRINO_METADATA).associateBy { it.dialect }

class SqlDocsTest {
    @Test
    fun docsHangOffEachDialectsMetadata() {
        for (metadata in listOf(DORIS_METADATA, CLICKHOUSE_METADATA, DUCKDB_METADATA, STARROCKS_METADATA)) {
            assertEquals(metadata.dialect, metadata.docs?.dialect)
        }
        assertNull(TRINO_METADATA.docs)
        // Sites without versioned paths are named as they are: their current or latest pages.
        assertEquals(mapOf("doris" to "4.x", "clickhouse" to "current", "duckdb" to "current", "starrocks" to "latest"),
            listOf(DORIS_METADATA, CLICKHOUSE_METADATA, DUCKDB_METADATA, STARROCKS_METADATA).associate { it.dialect to it.docs?.version })
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
    fun aNameBeingDefinedOrInsertedIntoIsNoTableFunction() {
        for ((dialect, sql) in listOf(
            "doris" to "CREATE TABLE <caret>jobs(k INT) DISTRIBUTED BY HASH(k)",
            "doris" to "INSERT INTO <caret>tasks(k) VALUES(1)",
            "doris" to "INSERT OVERWRITE TABLE <caret>numbers(k) SELECT 1",
            "clickhouse" to "CREATE TABLE <caret>url(x String) ENGINE=Memory",
            "clickhouse" to "INSERT INTO <caret>values(x) VALUES(1)",
            "duckdb" to "CREATE TABLE <caret>read_csv(x INT)",
            "starrocks" to "CREATE TABLE <caret>files(k INT)",
            "doris" to "SELECT * FROM t WHERE k IN (SELECT <caret>numbers(1))",
        )) assertNull(at(sql, dialect), sql)
    }

    @Test
    fun tableFunctionsInRelationListsAndTableCallsLink() {
        val sr = "https://docs.starrocks.io/docs/sql-reference/sql-functions/table-functions/"
        assertEquals("${sr}generate_series/", at("SELECT * FROM TABLE(gen<caret>erate_series(1, 3))", "starrocks")?.url)
        assertEquals("${sr}native_query/", at("SELECT * FROM TABLE(jdbc0.native<caret>_query('SELECT 1'))", "starrocks")?.url)
        assertEquals("${duckdb}data/csv/overview", at("SELECT * FROM t, read<caret>_csv('x.csv') AS c", "duckdb")?.url)
        assertEquals("${duckdb}data/csv/overview", at("SELECT * FROM t AS a, LATERAL read<caret>_csv('x.csv')", "duckdb")?.url)
        assertNull(at("SELECT a, read<caret>_csv('x.csv') FROM t", "duckdb"))
        // Doris writes through a table function given properties; ClickHouse through INSERT INTO FUNCTION.
        assertEquals("${doris}sql-functions/table-valued-functions/s3/", at("INSERT INTO <caret>s3(\"uri\" = \"s3://b/p/\") SELECT 1")?.url)
        assertEquals("${clickhouse}reference/functions/table-functions/s3", at("INSERT INTO FUNCTION <caret>s3('https://b/p.csv', 'CSV') SELECT 1", "clickhouse")?.url)
    }

    @Test
    fun clauseAndEngineWordsUsedAsFunctionsOrColumnsLinkNothing() {
        for ((dialect, sql) in listOf(
            "clickhouse" to "SELECT for<caret>mat('x', 1)",
            "clickhouse" to "SELECT for<caret>mat FROM t",
            "clickhouse" to "SELECT a, for<caret>mat FROM t",
            "clickhouse" to "SELECT set<caret>tings FROM system.query_log",
            "clickhouse" to "SELECT sam<caret>ple FROM t",
            "clickhouse" to "SELECT x FROM t WHERE sam<caret>ple = 1",
            "clickhouse" to "SELECT engine = Merge<caret>Tree FROM t",
            "clickhouse" to "SELECT eng<caret>ine FROM system.tables",
            "duckdb" to "SELECT piv<caret>ot FROM t",
            "duckdb" to "SELECT a, unpiv<caret>ot FROM t",
        )) assertNull(at(sql, dialect), sql)
        // The clauses themselves still link.
        assertEquals("${clickhouse}reference/statements/select/format", at("SELECT a FROM t FOR<caret>MAT JSONEachRow", "clickhouse")?.url)
        assertEquals("${clickhouse}concepts/features/configuration/settings/settings-query-level",
            at("SELECT a FROM t SET<caret>TINGS max_threads = 1", "clickhouse")?.url)
        assertEquals("${clickhouse}reference/statements/select/sample", at("SELECT a FROM t SAM<caret>PLE 0.1", "clickhouse")?.url)
        assertEquals("${clickhouse}reference/engines/table-engines/mergetree-family/mergetree",
            at("CREATE TABLE t (x UInt8) ENGINE = Merge<caret>Tree ORDER BY x", "clickhouse")?.url)
        assertEquals("${duckdb}sql/statements/pivot", at("SELECT * FROM t PIV<caret>OT (sum(v) FOR k IN ('a', 'b'))", "duckdb")?.url)
    }

    @Test
    fun theTokenHoldingTheCaretDecidesThenTheWordItEnds() {
        val mergeTree = "${clickhouse}reference/engines/table-engines/mergetree-family/mergetree"
        assertEquals(mergeTree, at("CREATE TABLE t (x UInt8) ENGINE=<caret>MergeTree ORDER BY x", "clickhouse")?.url)
        assertEquals(mergeTree, at("CREATE TABLE t (x UInt8) ENGINE=MergeTree<caret> ORDER BY x", "clickhouse")?.url)
        val sr = "https://docs.starrocks.io/docs/sql-reference/sql-functions/table-functions/"
        assertEquals("${sr}generate_series/", at("SELECT * FROM TABLE(<caret>generate_series(1, 3))", "starrocks")?.url)
        assertNull(at("CREATE TABLE t (x UInt8) ENGINE <caret>= MergeTree", "clickhouse"))
    }

    @Test
    fun alternativesBelongToOneWordAndEachBareWordLinks() {
        for ((sql, page) in listOf(
            "<caret>CHECKPOINT" to "checkpoint", "FORCE <caret>CHECKPOINT" to "checkpoint",
            "<caret>BEGIN" to "transactions", "BEGIN <caret>TRANSACTION" to "transactions", "<caret>COMMIT" to "transactions",
            "<caret>ROLLBACK" to "transactions", "<caret>ABORT" to "transactions", "START <caret>TRANSACTION" to "transactions",
        )) assertEquals("${duckdb}sql/statements/$page", at(sql, "duckdb")?.url, sql)
        // Alternatives spanning words, as `CHECKPOINT|FORCE CHECKPOINT` reads, are refused when the entry is made.
        assertFailsWith<IllegalArgumentException> { DocsEntry("CHECKPOINT|FORCE CHECKPOINT", "p", "t") }
        assertFailsWith<IllegalArgumentException> { DocsEntry("BEGIN|START TRANSACTION|BEGIN", "p", "t") }
    }

    @Test
    fun eachFormLinksItsOwnPage() {
        val mv = "${doris}sql-statements/table-and-view/"
        assertEquals("${mv}sync-materialized-view/CREATE-MATERIALIZED-VIEW/", at("CREATE MATERIALIZED <caret>VIEW mv AS SELECT k, sum(v) FROM t GROUP BY k")?.url)
        assertEquals("${mv}async-materialized-view/CREATE-ASYNC-MATERIALIZED-VIEW/",
            at("CREATE MATERIALIZED <caret>VIEW db.mv BUILD IMMEDIATE REFRESH AUTO ON MANUAL AS SELECT 1")?.url)
        assertEquals("${mv}async-materialized-view/CREATE-ASYNC-MATERIALIZED-VIEW/", at("CREATE MATERIALIZED <caret>VIEW IF NOT EXISTS mv (k) AS SELECT 1")?.url)
        assertNull(at("CREATE MATERIALIZED <caret>VIEW"), "not yet decided")
        for (name in listOf("parquet_metadata", "parquet_schema", "parquet_file_metadata", "parquet_kv_metadata", "parquet_bloom_probe", "hdfs", "iceberg_meta")) {
            assertNull(at("SELECT * FROM <caret>$name(\"uri\" = \"s3://b/p\")"), name)
        }
        assertEquals("${doris}sql-statements/cluster-management/storage-management/SET-DEFAULT-STORAGE-VAULT/", at("SET <caret>DEFAULT STORAGE VAULT v")?.url)
        assertEquals("${doris}sql-statements/account-management/SET-LDAP_ADMIN_PASSWORD/", at("<caret>SET LDAP_ADMIN_PASSWORD PASSWORD('x')")?.url)
        assertEquals("${doris}sql-statements/account-management/SET-PASSWORD/", at("<caret>SET PASSWORD = PASSWORD('x')")?.url)
        assertNull(at("<caret>SET TRANSACTION ISOLATION LEVEL READ COMMITTED"), "no page for it, and not a variable")
        val ch = "${clickhouse}reference/functions/table-functions/"
        assertEquals("${ch}icebergCluster", at("SELECT * FROM <caret>icebergS3Cluster('c', 'https://b/t')", "clickhouse")?.url)
        assertEquals("${ch}paimonCluster", at("SELECT * FROM <caret>paimonAzureCluster('c', 'x')", "clickhouse")?.url)
        assertEquals("${ch}iceberg", at("SELECT * FROM <caret>icebergS3('https://b/t')", "clickhouse")?.url)
        assertNull(at("<caret>WATCH lv", "clickhouse"))
    }

    @Test
    fun aStatementBeginningWithWithIsItsMainStatement() {
        assertEquals("${doris}sql-statements/data-query/SELECT/", at("WITH c AS (SELECT 1 AS k), d (k) AS (SELECT 2) <caret>SELECT * FROM c, d")?.url)
        assertEquals("${doris}sql-statements/data-modification/DML/INSERT/", at("WITH RECURSIVE c AS (SELECT 1) INSERT <caret>INTO t SELECT * FROM c")?.url)
        assertNull(at("<caret>WITH c AS (SELECT 1) SELECT * FROM c"))
        assertNull(at("WITH c AS (<caret>SELECT 1) SELECT * FROM c"))
    }

    @Test
    fun nothingIsOfferedForUnrelatedTokensOrUnknownDialects() {
        for (sql in listOf("SELECT k FROM <caret>t", "SELECT 'CREATE <caret>JOB'", "SELECT 1 -- CREATE <caret>JOB", "CREATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO <caret>t SELECT 1",
            "SELECT k <caret>FROM t", "SELECT `create` FROM t WHERE k = <caret>1")) assertNull(at(sql), sql)
        assertNull(at("CREATE <caret>JOB j", "trino"))
    }
}
