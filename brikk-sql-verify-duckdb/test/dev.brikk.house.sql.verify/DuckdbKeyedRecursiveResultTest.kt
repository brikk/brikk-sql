package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class DuckdbKeyedRecursiveResultTest {
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
        // A bad recursive rewrite must fail this test, not run forever. Every fixture below
        // has an explicit finite frontier, or native UNION keyed-state convergence.
        statement.queryTimeout = 5
        statement.executeQuery(sql).use { result -> buildList {
            while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
        } }
    }
    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.check(sql: String, expected: List<List<String?>>) {
        assertEquals(expected, rows(sql), "authored: $sql")
        assertEquals(expected, rows(Dialects.DUCKDB.parseOne(sql).sql("duckdb")), "generated: $sql")
    }

    @Test
    fun documentedAverageAndMaximumKeepDefaultLastPayloads() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("WITH RECURSIVE tbl(a,b) USING KEY(a,avg(b)) AS (SELECT 1,5 UNION ALL SELECT a,b-1 FROM tbl WHERE b>0) TABLE tbl",
                listOf(listOf("1", "2.5")))
            c.check("WITH RECURSIVE tbl(a,b,c) USING KEY(a,max(b)) AS (SELECT 1,5,'x' UNION ALL SELECT a,b-1,'y' FROM tbl WHERE b>0) SELECT * FROM tbl",
                listOf(listOf("1", "5", "y")))
            c.check("WITH RECURSIVE t(k,v) USING KEY(k,min(abs(v)) AS v) AS (SELECT 1,-5 UNION SELECT k+1,-v FROM t WHERE k<2) SELECT * FROM t ORDER BY k",
                listOf(listOf("1", "5"), listOf("2", "5")))
        }
    }

    @Test
    fun recurringReadsFinalizedAggregateValuesWhileUnionAllForwardsRawCandidates() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val sql = "WITH RECURSIVE t(a,b,c,kind) USING KEY(a,avg(b),list(c)) AS (SELECT 1,3,NULL::DOUBLE,'seed' UNION ALL SELECT n.a,n.b-1,r.b,typeof(n.b)||'/'||typeof(r.b) FROM t n,recurring.t r WHERE n.b>0) SELECT * FROM t"
            c.check(sql, listOf(listOf("1", "1.5", "[NULL, 3.0, 2.5, 2.0]", "INTEGER/DOUBLE")))
            c.check("WITH RECURSIVE t(k,v) USING KEY(k) AS (SELECT 1,0 UNION ALL SELECT n.k,r.v+1 FROM t n JOIN recurring.t r USING(k) WHERE n.v<3) SELECT * FROM t",
                listOf(listOf("1", "3")))
        }
    }

    @Test
    fun unionWorkingTableUsesAggregateOutputTypesAndStableFinalizedStateTerminates() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.check("WITH RECURSIVE t(k,total,kind) USING KEY(k,sum(total)) AS (SELECT 1,1::INT,'seed' UNION SELECT k,1,typeof(total) FROM t WHERE total<3) SELECT * FROM t",
                listOf(listOf("1", "3", "HUGEINT")))
            c.check("WITH RECURSIVE t(k,v) USING KEY(k,avg(v)) AS (VALUES (1,0),(1,2) UNION SELECT k,x FROM t,(VALUES(0),(2)) r(x)) SELECT * FROM t",
                listOf(listOf("1", "1.0")))
            c.check("WITH RECURSIVE t(k,v) USING KEY(k,min(v)) AS (VALUES (1,5) UNION SELECT k,v+d FROM t,(VALUES(0),(0),(1),(2)) r(d)) SELECT * FROM t",
                listOf(listOf("1", "5")))
        }
    }

    @Test
    fun unionAndUnionAllDoNotHaveTheSameRecursiveWorkEvenWhenFinalRowsMatch() {
        for (generated in listOf(false, true)) for ((operator, evaluations) in listOf("UNION" to "1", "UNION ALL" to "4")) {
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                c.execute("CREATE SEQUENCE frontier_seq START 1")
                val sql = "WITH RECURSIVE t(k,v) USING KEY(k) AS (SELECT 1,0 $operator SELECT k,v FROM t WHERE nextval('frontier_seq')<=3) SELECT * FROM t"
                assertEquals(listOf(listOf("1", "0")), c.rows(if (generated) Dialects.DUCKDB.parseOne(sql).sql("duckdb") else sql))
                assertEquals(listOf(listOf(evaluations)), c.rows("SELECT currval('frontier_seq')"), "$operator generated=$generated")
            }
        }
    }

    @Test
    fun minimumPayloadConvergesOnTheDocumentedCyclicShortestPathGraph() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("CREATE TABLE weighted_edges(src INT,dst INT,weight INT)")
            c.execute("INSERT INTO weighted_edges VALUES (1,2,4),(1,3,1),(3,2,1),(2,4,1),(3,4,5),(4,1,1)")
            c.check("WITH RECURSIVE shortest_paths(node,dist) USING KEY(node,min(dist)) AS (SELECT 1,0 UNION SELECT e.dst,sp.dist+e.weight FROM shortest_paths sp JOIN weighted_edges e ON e.src=sp.node) SELECT * FROM shortest_paths ORDER BY node",
                listOf(listOf("1", "0"), listOf("2", "2"), listOf("3", "1"), listOf("4", "3")))
        }
    }

    @Test
    fun finalTypesIncludePromotedSumsListsAndAliasedConstantAggregates() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val expressions = listOf("avg(b)" to "DOUBLE", "sum(b)" to "HUGEINT", "list(b)" to "INTEGER[]", "count(b) AS b" to "BIGINT")
            for ((aggregate, type) in expressions) {
                val sql = "WITH RECURSIVE t(a,b) USING KEY(a,$aggregate) AS (SELECT 1,3 UNION ALL SELECT a,b-1 FROM t WHERE b>0) SELECT * FROM t"
                c.createStatement().use { statement ->
                    statement.queryTimeout = 5
                    statement.executeQuery(Dialects.DUCKDB.parseOne(sql).sql("duckdb")).use { result ->
                        assertEquals(type, result.metaData.getColumnTypeName(2), aggregate)
                    }
                }
            }
            c.check("WITH RECURSIVE t(a,b) USING KEY(a,string_agg(':a') AS b) AS (VALUES (1,2),(1,3) UNION ALL SELECT a,b+1 FROM t WHERE b<4) SELECT * FROM t",
                listOf(listOf("1", ":a,:a,:a,:a,:a")))
            c.check("WITH RECURSIVE t(k,v) USING KEY(k,avg(v)) AS (SELECT 1,1.00::DECIMAL(8,2) UNION ALL SELECT k,v+1 FROM t WHERE v<3) SELECT * FROM t",
                listOf(listOf("1", "2.0")))
            c.check("WITH RECURSIVE t(k,b,total) USING KEY(k,sum(b) AS total) AS (SELECT 1,3,0 UNION ALL SELECT k,b-1,0 FROM t WHERE b>0) SELECT * FROM t",
                listOf(listOf("1", "0", "6")))
        }
    }

    @Test
    fun invalidKeysDestinationsModifiersAndRecurringPlacementRemainNativeErrors() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (keys in listOf("avg(b)", "a,avg(a)", "a,avg(b),max(b)", "a+1,avg(b)", "a,lower(b)",
                "a,avg(b+1)", "a,avg(b) AS missing", "a,avg(missing) AS b", "a,avg(DISTINCT b)",
                "a,avg(b) FILTER(WHERE b>0)", "a,list(b ORDER BY b)")) {
                val sql = "WITH RECURSIVE t(a,b) USING KEY($keys) AS (SELECT 1,3 UNION ALL SELECT a,b-1 FROM t WHERE b>0) SELECT * FROM t"
                for (text in listOf(sql, Dialects.DUCKDB.parseOne(sql).sql("duckdb"))) {
                    val error = assertFailsWith<SQLException>(keys) { c.rows(text) }
                    assertTrue("Binder Error" in error.message.orEmpty(), error.message)
                }
            }
            val outside = "WITH RECURSIVE t(a,b) USING KEY(a) AS (SELECT 1,3 UNION ALL SELECT a,b-1 FROM t WHERE b>0) SELECT * FROM recurring.t"
            assertFailsWith<SQLException> { c.rows(Dialects.DUCKDB.parseOne(outside).sql("duckdb")) }
        }
    }

    @Test
    fun finalDrainDoesNotReexecuteTheRecursiveBuildAcrossVectorBoundaries() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("SET threads=1")
            c.execute("SET enable_caching_operators=true")
            c.execute("CREATE SEQUENCE drain_seq START 1")
            val sql = "WITH RECURSIVE t(k,v,x) USING KEY(k) AS (SELECT i,0,NULL::BIGINT FROM range(2049) r(i) UNION ALL SELECT n.k,n.v+1,build.x FROM t n JOIN (SELECT i AS k,nextval('drain_seq') AS x FROM range(2049) r(i)) build USING(k) WHERE n.v<0) SELECT count(*),min(v),max(v),currval('drain_seq') FROM t"
            assertEquals(listOf(listOf("2049", "0", "0", "2049")), c.rows(if (generated) Dialects.DUCKDB.parseOne(sql).sql("duckdb") else sql))
        }
    }

    @Test
    fun ordinaryRecursiveCtesRetainAppendAndDedupSemantics() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (operator in listOf("UNION", "UNION ALL")) c.check(
                "WITH RECURSIVE counter(n) AS (SELECT 1 $operator SELECT n+1 FROM counter WHERE n<4) SELECT * FROM counter ORDER BY n",
                (1..4).map { listOf(it.toString()) })
            c.check("WITH RECURSIVE counter(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM counter WHERE (SELECT max(n) FROM recurring.counter)<4) SELECT * FROM counter ORDER BY n",
                (1..4).map { listOf(it.toString()) })
        }
    }
}
