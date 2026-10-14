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
}
