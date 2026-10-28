package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in native probes; both FE and BE must report Doris 4.1.5. Historical evidence stays versioned. */
class DorisCatchupResultTest {
    private val client = listOf("mysql", "--default-character-set=utf8mb4", "-uroot", "-h127.0.0.1", "-P9030", "-B", "-e")

    @Test
    fun liveDorisFrontendAndBackendUseCurrentSupportedRelease() {
        val container = System.getenv("BRIKK_DORIS_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_DORIS_TEST_CONTAINER is not configured")
        val version = Regex("doris-4\\.1\\.5(?:-|\\s|$)")
        for (query in listOf("SHOW FRONTENDS", "SHOW BACKENDS")) {
            val output = runCatchupEngineSql(container!!, client, query)
            assertTrue(version.containsMatchIn(output), "Expected Doris 4.1.5 in $query: $output")
        }
    }

    @Test
    fun liveDorisSharedEscapesCommentsIfAndDateArithmeticMatchNativeResults() {
        val container = System.getenv("BRIKK_DORIS_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_DORIS_TEST_CONTAINER is not configured")
        for (source in listOf(
            "SELECT IF(NULL,1,2) AS value", "SELECT CASE WHEN NULL THEN 1 ELSE 2 END AS value",
            "SELECT DATE_ADD(DATE('2024-01-01 12:34:56'),INTERVAL 1 HOUR) AS value",
            "SELECT DATE_SUB(DATE('2024-01-01 12:34:56'),INTERVAL 1 HOUR) AS value",
            "SELECT HEX('\\0\\Z\\a\\f\\v\\q\\%\\_') AS bytes", "SELECT HEX('😀\\n') AS bytes",
            "SELECT 1--2 AS swallowed", "SELECT 1--comment\r,2 AS second",
        )) {
            val output = Dialects.DORIS.parseOne(source).sql("doris")
            assertEquals(runCatchupEngineSql(container!!, client, source),
                runCatchupEngineSql(container, client, output), output)
        }
    }

    @Test
    fun liveDorisGenericPropertiesAndNormalizedInsertKeepActualRows() {
        val container = System.getenv("BRIKK_DORIS_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_DORIS_TEST_CONTAINER is not configured")
        val nonce = java.lang.Long.toUnsignedString(System.nanoTime(), 36)
        val statements = listOf("CREATE TABLE t(id INT,v VARCHAR(10)) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 " +
            "PROPERTIES('replication_num'='1')", "INSERT INTO t VALUES(1,'a'),(2,'b')")
        fun run(which: String, sql: List<String>) = runCatchupEngineSql(container!!, client,
            "CREATE DATABASE doris_${which}_$nonce; USE doris_${which}_$nonce; " +
                sql.joinToString("; ") + "; SHOW CREATE TABLE t; SELECT * FROM t ORDER BY id")
        val output = statements.map { Dialects.DORIS.parseOne(it).sql("doris") }.toMutableList()
        // VALUE is MySQL syntax, not native Doris syntax. The normalized target is VALUES.
        output[1] = Dialects.MYSQL.parseOne("INSERT INTO t VALUE(1,'a'),(2,'b')").sql("doris")
        assertEquals(run("source", statements), run("output", output))
    }

    @Test
    fun liveDorisNullableBooleanOrAndMultibranchCaseKeepAllTruthTableRows() {
        val container = System.getenv("BRIKK_DORIS_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_DORIS_TEST_CONTAINER is not configured")
        val database = "doris_bool_${java.lang.Long.toUnsignedString(System.nanoTime(), 36)}"
        runCatchupEngineSql(container!!, client, "CREATE DATABASE $database; USE $database; " +
            "CREATE TABLE t(id INT, a BOOLEAN, b BOOLEAN) DUPLICATE KEY(id) " +
            "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1'); " +
            "INSERT INTO t VALUES (1,TRUE,TRUE),(2,TRUE,FALSE),(3,TRUE,NULL)," +
            "(4,FALSE,TRUE),(5,FALSE,FALSE),(6,FALSE,NULL),(7,NULL,TRUE),(8,NULL,FALSE),(9,NULL,NULL)")
        // Real nullable table columns, not constant-folded scalar expressions. This
        // exercises the CASE/OR execution family fixed by the 4.1.4.1 hotfix.
        val source = "SELECT id, a OR b AS logical_or, CASE WHEN id < 0 THEN 'negative' " +
            "WHEN a OR b THEN 'yes' WHEN a IS NULL OR b IS NULL THEN 'unknown' ELSE 'no' END AS choice " +
            "FROM t ORDER BY id"
        val expected = "id\tlogical_or\tchoice\n" +
            "1\t1\tyes\n2\t1\tyes\n3\t1\tyes\n4\t1\tyes\n5\t0\tno\n" +
            "6\tNULL\tunknown\n7\t1\tyes\n8\tNULL\tunknown\n9\tNULL\tunknown"
        for (query in listOf(source, Dialects.DORIS.parseOne(source).sql("doris"))) {
            assertEquals(expected, runCatchupEngineSql(container, client, "USE $database; $query"), query)
        }
    }

    @Test
    fun liveDorisHotfixJsonBooleanNullPayloadKeepsOrAndCaseRowsWithBothExecutionModes() {
        val container = System.getenv("BRIKK_DORIS_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_DORIS_TEST_CONTAINER is not configured")
        val database = "doris_json_bool_${java.lang.Long.toUnsignedString(System.nanoTime(), 36)}"
        runCatchupEngineSql(container!!, client, "CREATE DATABASE $database; USE $database; " +
            "CREATE TABLE t(id BIGINT, j STRING, a BIGINT, b BIGINT) DUPLICATE KEY(id) " +
            "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1'); " +
            "INSERT INTO t SELECT number, CASE WHEN number % 100 = 0 THEN '{\"flag\":true}' " +
            "ELSE CONCAT('{\"n\":',number,'}') END, " +
            "number + CASE number % 3 WHEN 0 THEN 10 WHEN 1 THEN -100 ELSE -200000 END, " +
            "number FROM numbers('number'='100000')")
        // Adapted from the release-tag regression for apache/doris#68401. Missing
        // JSON fields produce nullable Boolean payloads that exposed the BE crash;
        // ordinary SQL NULL literals alone do not reproduce that execution path.
        val json = "JSON_PARSE_ERROR_TO_NULL(j)"
        val flag = "JSON_EXTRACT_BOOL($json, '$.flag')"
        val choices = "CASE WHEN $flag OR a > b THEN 'then_b' WHEN a < b THEN 'then_a' ELSE 'else' END"
        val cases = listOf(
            "SELECT v, COUNT(*) AS n FROM (SELECT $flag OR (a > b) AS v FROM t) q GROUP BY v ORDER BY v" to
                "v\tn\nNULL\t66000\n1\t34000",
            "SELECT v, COUNT(*) AS n FROM (SELECT ($json IS NOT NULL AND $flag) OR (a > b) AS v FROM t) q " +
                "GROUP BY v ORDER BY v" to "v\tn\nNULL\t66000\n1\t34000",
            "SELECT COUNT(*) AS n FROM t WHERE (CASE WHEN $flag OR a > b THEN b WHEN a < b THEN a END) IS NOT NULL" to
                "n\n100000",
            "SELECT $choices AS branch, COUNT(*) AS n FROM t GROUP BY branch ORDER BY branch" to
                "branch\tn\nthen_a\t66000\nthen_b\t34000",
        )
        for (shortCircuit in listOf(false, true)) {
            val prefix = "USE $database; SET enable_sql_cache = false; SET short_circuit_evaluation = $shortCircuit; "
            for ((source, expected) in cases) {
                for (query in listOf(source, Dialects.DORIS.parseOne(source).sql("doris"))) {
                    assertEquals(expected, runCatchupEngineSql(container, client, prefix + query), query)
                }
            }
        }
    }

    @Test
    fun liveDorisVariantNullsAndTimezoneAwareCoalesceKeepNativeValues() {
        val container = System.getenv("BRIKK_DORIS_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_DORIS_TEST_CONTAINER is not configured")
        for (source in listOf(
            "SELECT PARSE_TO_VARIANT('{\"k\":1}')['k'] AS value, PARSE_TO_VARIANT(NULL) IS NULL AS source_null, " +
                "TRY_PARSE_TO_VARIANT(NULL) IS NULL AS try_null",
            "SELECT TRY_PARSE_TO_VARIANT('{') IS NULL AS failed_parse",
            "SELECT CAST(COALESCE(CAST(NULL AS TIMESTAMPTZ(6)), " +
                "CAST('2024-01-15 12:00:00.123456 +00:00' AS TIMESTAMPTZ(6))) AS VARCHAR) AS value",
        )) {
            val output = Dialects.DORIS.parseOne(source).sql("doris")
            val prefix = "SET time_zone = '+00:00'; "
            assertEquals(runCatchupEngineSql(container!!, client, prefix + source),
                runCatchupEngineSql(container, client, prefix + output), output)
        }
    }
}
