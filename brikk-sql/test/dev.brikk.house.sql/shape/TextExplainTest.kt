package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextExplainTest {
    private val builtins = setOf("known_builtin")

    private fun prepare(sql: String, dialect: String = "trino") = prepareTextExplain(sql, dialect) { it.lowercase() in builtins }

    private fun assertReady(expected: String, sql: String, dialect: String = "trino") =
        assertEquals(ExplainPreparation.Ready(expected), prepare(sql, dialect), "$dialect: $sql")

    private fun assertRefused(sql: String, dialect: String = "trino"): String =
        assertIs<ExplainPreparation.Refused>(prepare(sql, dialect), "$dialect: $sql").reason

    @Test
    fun plainQueriesAreExplainedAsWritten() {
        for (dialect in listOf("trino", "doris")) {
            assertReady("EXPLAIN SELECT a FROM t", "SELECT a FROM t;", dialect)
            assertReady("EXPLAIN SELECT a FROM t", "  SELECT a FROM t  ", dialect)
            assertReady("EXPLAIN WITH c AS (SELECT 1 AS x) SELECT x FROM c UNION ALL SELECT 2", "WITH c AS (SELECT 1 AS x) SELECT x FROM c UNION ALL SELECT 2", dialect)
            assertReady("EXPLAIN SELECT (SELECT max(x) FROM t) FROM u WHERE y IN (SELECT y FROM v)", "SELECT (SELECT max(x) FROM t) FROM u WHERE y IN (SELECT y FROM v)", dialect)
            assertReady("EXPLAIN SELECT known_builtin(a) FROM t", "SELECT known_builtin(a) FROM t", dialect)
        }
        assertReady("EXPLAIN VALUES 1, 2", "VALUES 1, 2")
        assertReady("EXPLAIN SELECT x FROM UNNEST(ARRAY[1, 2]) AS u(x)", "SELECT x FROM UNNEST(ARRAY[1, 2]) AS u(x)")
        assertReady("EXPLAIN SELECT x FROM t LATERAL VIEW explode(a) v AS x", "SELECT x FROM t LATERAL VIEW explode(a) v AS x", "doris")
    }

    /** Trino 483 creates the table of an EXPLAIN CREATE TABLE AS; no statement but a query is explained. */
    @Test
    fun onlyQueriesAreExplained() {
        for (dialect in listOf("trino", "doris")) {
            for (sql in listOf(
                "CREATE TABLE t AS SELECT 1 AS x",
                "INSERT INTO t SELECT 1",
                "DELETE FROM t",
                "DROP TABLE t",
                "EXPLAIN SELECT 1",
                "EXPLAIN ANALYZE SELECT 1",
            )) assertRefused(sql, dialect)
        }
    }

    @Test
    fun queriesThatMayRunCodeWhilePlanningAreRefused() {
        assertTrue("table function" in assertRefused("SELECT * FROM TABLE(system.query(query => 'DELETE FROM t'))"))
        assertTrue("table function" in assertRefused("SELECT * FROM t JOIN TABLE(exclude_columns(input => TABLE(t), columns => DESCRIPTOR(a))) ON true"))
        assertTrue("table function" in assertRefused("SELECT * FROM numbers(\"number\" = \"3\")", "doris"))
        assertTrue("routine" in assertRefused("SELECT memory.default.known_builtin(1)"))
        assertTrue("my_udf" in assertRefused("SELECT my_udf(a) FROM t"))
        assertTrue("my_udf" in assertRefused("SELECT a FROM t WHERE a IN (SELECT my_udf(b) FROM u)", "doris"))
    }

    @Test
    fun scriptsPipesAndUnreadableTextAreRefused() {
        assertRefused("SELECT 1; SELECT 2")
        assertRefused("")
        assertRefused("FROM t |> WHERE a > 1")
        assertRefused("SELECT 'unterminated")
        assertRefused("SELECT FROM WHERE (")
    }

    @Test
    fun onlyEnginesKnownToPlanWithoutRunningAreExplained() {
        assertTrue("table function arguments" in assertRefused("SELECT * FROM range(nextval('s'))", "duckdb"))
        assertTrue("table function arguments" in assertRefused("SELECT 1", "duckdb"))
        for (dialect in listOf("postgres", "mysql", "clickhouse")) assertRefused("SELECT 1", dialect)
    }

    @Test
    fun planTextJoinsTheFirstColumnOfEachRow() {
        assertEquals("PLAN FRAGMENT 0\n  OUTPUT EXPRS:\n\n  RESULT SINK", explainPlanText(listOf("PLAN FRAGMENT 0", "  OUTPUT EXPRS:", null, "  RESULT SINK", "")))
        assertEquals("Fragment 0 [SINGLE]\n    Output layout: [expr]", explainPlanText(listOf("Fragment 0 [SINGLE]\n    Output layout: [expr]\n")))
        assertEquals("", explainPlanText(emptyList()))
    }
}
