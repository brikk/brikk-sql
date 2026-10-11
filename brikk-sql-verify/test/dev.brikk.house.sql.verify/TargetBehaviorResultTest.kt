package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.qualify
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetBehaviorResultTest {
    @Test
    fun nativeDuckdbSemiJoinBindingAndDuplicateAliasesPreserveRows() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for (source in listOf("WITH w AS (SELECT 1 AS d),y AS (SELECT 1 AS b),z AS (SELECT -2 AS b) " +
                "SELECT w.d FROM w SEMI JOIN y ON b>0 JOIN z ON TRUE",
                "SELECT a AS j,-b AS j FROM (VALUES (2,1),(1,3),(3,2)) AS x(a,b) GROUP BY 1,2 ORDER BY 1,-b")) {
                fun rows(sql: String) = connection.createStatement().use { statement -> statement.executeQuery(sql).use { result ->
                    buildList { while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) }) }
                } }
                val output = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB).sql("duckdb")
                assertEquals(rows(source), rows(output), output)
            }
        }
    }

    @Test
    fun livePostgresOrdinalitySearchCycleAndBooleanUpdatesKeepNativeResults() {
        val container = System.getenv("BRIKK_POSTGRES_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_POSTGRES_TEST_CONTAINER is not configured")
        val client = listOf("psql", "-U", "postgres", "-A", "-P", "footer=off", "-v", "ON_ERROR_STOP=1", "-c")
        assertEquals("type\nbigint", runCatchupEngineSql(container!!, client,
            "SELECT PG_TYPEOF(r.idx) AS type FROM LATERAL UNNEST(ARRAY[10]) WITH ORDINALITY AS r(v,idx)"))
        for (source in listOf("SELECT r.v,r.idx FROM LATERAL UNNEST(ARRAY[10,20]) WITH ORDINALITY AS r(v,idx)",
            "WITH RECURSIVE q(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM q WHERE n<3) SEARCH DEPTH FIRST BY n SET s " +
                "CYCLE n SET flag TO 'Y' DEFAULT 'N' USING path SELECT n,flag FROM q ORDER BY s")) {
            assertEquals(runCatchupEngineSql(container!!, client, source),
                runCatchupEngineSql(container, client, Dialects.POSTGRES.parseOne(source).sql("postgres")))
        }
        val source = "UPDATE t SET a=x OR y,b=x AND y"
        fun run(query: String) = runCatchupEngineSql(container!!, client,
            "BEGIN; CREATE TEMP TABLE t(a BOOLEAN,b BOOLEAN,x BOOLEAN,y BOOLEAN); INSERT INTO t VALUES(NULL,NULL,TRUE,FALSE); " +
                "$query; SELECT a,b FROM t; ROLLBACK")
        assertEquals(run(source), run(Dialects.POSTGRES.parseOne(source).sql("postgres")))
    }

    @Test
    fun liveTrinoSingularFetchAndOffsetKeepPaginationResults() {
        val container = System.getenv("BRIKK_TRINO_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_TRINO_TEST_CONTAINER is not configured")
        val client = listOf("trino", "--output-format=TSV_HEADER", "--execute")
        for (tail in listOf("FETCH FIRST ROW ONLY", "OFFSET 1 ROW FETCH NEXT 1 ROW ONLY", "FETCH NEXT 1 ROW WITH TIES")) {
            val source = "SELECT x FROM (VALUES 1,2,2,3) AS t(x) ORDER BY x $tail"
            assertEquals(runCatchupEngineSql(container!!, client, source),
                runCatchupEngineSql(container, client, Dialects.TRINO.parseOne(source).sql("trino")))
        }
    }
}
