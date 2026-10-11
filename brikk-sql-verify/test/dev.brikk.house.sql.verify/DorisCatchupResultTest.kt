package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/** Cached 4.1.3 native engine probe; not a claim of live 4.1.4.1-specific features. */
class DorisCatchupResultTest {
    private val client = listOf("mysql", "--default-character-set=utf8mb4", "-uroot", "-h127.0.0.1", "-P9030", "-B", "-e")

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
}
