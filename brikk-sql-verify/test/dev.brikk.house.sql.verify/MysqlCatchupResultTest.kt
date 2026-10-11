package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class MysqlCatchupResultTest {
    private val client = listOf("mysql", "--default-character-set=utf8mb4", "-uroot", "-B", "-e")

    @Test
    fun liveMysqlEscapesCommentsConditionsDateConversionsAndWeightsKeepResults() {
        val container = System.getenv("BRIKK_MYSQL_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_MYSQL_TEST_CONTAINER is not configured")
        for (source in listOf(
            "SELECT 1--2 AS value", "SELECT 1 AS value -- comment\r,2 AS swallowed", "SELECT 1 AS value # comment\r,2 AS swallowed",
            "SELECT HEX('\\0\\Z\\a\\f\\v\\q\\%\\_') AS bytes", "SELECT HEX('😀\\n\\b\\t') AS bytes",
            "SELECT IF(NULL,1,2) AS value", "SELECT IF(1,1,IF(0,2,3)) AS value", "SELECT CASE WHEN NULL THEN 1 ELSE 2 END AS value",
            "SELECT DATE_ADD(DATE('2024-01-01 12:34:56'), INTERVAL 1 HOUR) AS value",
            "SELECT DATE_SUB(DATE('2024-01-01 12:34:56'), INTERVAL 1 HOUR) AS value",
            "SELECT HEX(WEIGHT_STRING('ab' AS BINARY(4))) AS value", "SELECT HEX(WEIGHT_STRING('ab' AS CHAR(4))) AS value",
            "SELECT HEX(WEIGHT_STRING('ab',0,3,0)) AS value", "SELECT CAST('abcd' AS CHAR(2) CHARACTER SET latin1) AS value",
            "SELECT CONVERT('abcd',CHAR(2) CHARACTER SET latin1) AS value",
        )) {
            val output = Dialects.MYSQL.parseOne(source).sql("mysql")
            assertEquals(runCatchupEngineSql(container!!, client, source), runCatchupEngineSql(container, client, output), output)
        }
    }

    @Test
    fun liveMysqlDdlAndInsertValueSynonymsRetainSchemasAndRows() {
        val container = System.getenv("BRIKK_MYSQL_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_MYSQL_TEST_CONTAINER is not configured")
        val nonce = java.lang.Long.toUnsignedString(System.nanoTime(), 36)
        val cases = listOf(
            listOf("CREATE TABLE t(a INT)", "INSERT INTO t VALUE(1),(2)"),
            listOf("CREATE TABLE t(a INT,KEY k(a),CONSTRAINT c CHECK(a>0))", "ALTER TABLE t DROP KEY k, DROP CHECK c"),
            listOf("CREATE TABLE t(a INT SIGNED)", "ALTER TABLE t ADD COLUMN b BIGINT SIGNED NOT NULL DEFAULT 0"),
            listOf("CREATE TABLE t(a VARCHAR(5) ASCII,b VARCHAR(5) UNICODE)"),
            listOf("CREATE TABLE t(a VARCHAR(5) CHARSET utf8mb4) CHAR SET utf8mb4 ENGINE=InnoDB", "ALTER TABLE t COMMENT='hi' ROW_FORMAT=DYNAMIC"),
            listOf("CREATE TABLE t(a INT)", "ALTER TABLE t ADD PRIMARY KEY(a) KEY_BLOCK_SIZE=8 COMMENT 'key'"),
            listOf("CREATE TABLE t(a INT) ENGINE=InnoDB KEY_BLOCK_SIZE=8 MAX_ROWS=100"),
        )
        for ((index, statements) in cases.withIndex()) {
            fun run(which: String, queries: List<String>) = runCatchupEngineSql(container!!, client,
                "CREATE DATABASE mysql_${which}_${nonce}_$index; USE mysql_${which}_${nonce}_$index; " +
                    queries.joinToString("; ") + "; SHOW CREATE TABLE t; SELECT * FROM t")
            assertEquals(run("source", statements), run("output", statements.map { Dialects.MYSQL.parseOne(it).sql("mysql") }), statements.toString())
        }
    }
}
