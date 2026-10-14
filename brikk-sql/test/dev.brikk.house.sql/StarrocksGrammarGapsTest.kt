package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** StarRocks statements from real scripts that core failed to parse. */
class StarrocksGrammarGapsTest {
    private fun roundTrip(sql: String): String {
        val once = Dialects.forName("starrocks").parseOne(sql).sql("starrocks")
        assertEquals(once, Dialects.forName("starrocks").parseOne(once).sql("starrocks"), "stable: $sql")
        return once
    }

    /** S1: an empty partition list (dynamic partitions), as Doris accepts it. */
    @Test
    fun emptyRangePartitionList() {
        val sql = "CREATE TABLE d.t (k BIGINT, ts DATETIME) DUPLICATE KEY (k) PARTITION BY RANGE (ts) () DISTRIBUTED BY HASH (k) BUCKETS 1 PROPERTIES ('replication_num'='1')"
        assertEquals(sql, roundTrip(sql))
    }

    /** S2: administrative statements are commands, their text kept, as in Doris. */
    @Test
    fun cancelAndAdminStatementsAreCommands() {
        for (sql in listOf("CANCEL LOAD FROM d WHERE LABEL = 'x'", "ADMIN ABORT TRANSACTION WHERE txn_id = 1")) {
            assertIs<Command>(Dialects.forName("starrocks").parseOne(sql), sql)
            assertEquals(sql, roundTrip(sql))
        }
    }

    /** S3: StarRocks-only statements parse, at least as commands with their text kept. */
    @Test
    fun starrocksOnlyStatementsParse() {
        for (sql in listOf(
            "SUBMIT TASK j SCHEDULE EVERY(INTERVAL 2 MINUTE) AS INSERT INTO t SELECT 1",
            "REFRESH MATERIALIZED VIEW d.mv FORCE",
            "REFRESH MATERIALIZED VIEW d.mv WITH SYNC MODE",
            "REFRESH MATERIALIZED VIEW d.mv PARTITION START ('2024-01-01') END ('2024-02-01')",
            "DROP TASK j", "SHOW TASKS", "CREATE PIPE p AS INSERT INTO t SELECT * FROM FILES('path' = 's3://x')",
            "ALTER PIPE p SET ('k' = 'v')", "SHOW PIPES", "SET CATALOG x", "SHOW PROC '/backends'", "SHOW RESOURCE GROUPS", "SHOW TABLET FROM t",
        )) assertEquals(sql, roundTrip(sql))
        assertEquals("REFRESH MATERIALIZED VIEW d.mv", roundTrip("REFRESH MATERIALIZED VIEW d.mv"))
        assertIs<dev.brikk.house.sql.ast.Refresh>(Dialects.forName("starrocks").parseOne("REFRESH MATERIALIZED VIEW d.mv"))
    }
}
