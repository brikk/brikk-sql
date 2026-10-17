package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextExplainTest {
    private fun assertReady(expected: String, sql: String, dialect: String = "trino") =
        assertEquals(expected, assertIs<ExplainPreparation.Ready>(prepareTextExplain(sql, dialect), "$dialect: $sql").sql, "$dialect: $sql")

    private fun assertRefused(sql: String, dialect: String = "trino"): String =
        assertIs<ExplainPreparation.Refused>(prepareTextExplain(sql, dialect), "$dialect: $sql").reason

    @Test
    fun plainQueriesAreExplainedAsWritten() {
        for (dialect in listOf("trino", "doris")) {
            assertReady("EXPLAIN SELECT a FROM t", "SELECT a FROM t;", dialect)
            assertReady("EXPLAIN SELECT a FROM t", "  SELECT a FROM t  ", dialect)
            assertReady("EXPLAIN WITH c AS (SELECT 1 AS x) SELECT x FROM c UNION ALL SELECT 2", "WITH c AS (SELECT 1 AS x) SELECT x FROM c UNION ALL SELECT 2", dialect)
            assertReady("EXPLAIN SELECT (SELECT max(x) FROM t) FROM u WHERE y IN (SELECT y FROM v)", "SELECT (SELECT max(x) FROM t) FROM u WHERE y IN (SELECT y FROM v)", dialect)
        }
        assertReady("EXPLAIN VALUES 1, 2", "VALUES 1, 2")
        assertReady("EXPLAIN SELECT x FROM UNNEST(ARRAY[1, 2]) AS u(x)", "SELECT x FROM UNNEST(ARRAY[1, 2]) AS u(x)")
        assertReady("EXPLAIN SELECT x FROM t LATERAL VIEW explode(a) v AS x", "SELECT x FROM t LATERAL VIEW explode(a) v AS x", "doris")
    }

    /** Functions, UDFs, routines and hints are the engine's business, as in any query the console runs. */
    @Test
    fun queriesWithUdfsHintsAndUnknownFunctionsAreExplained() {
        for ((dialect, sql) in listOf(
            "trino" to "SELECT my_udf(a), sequence(1, 3), memory.default.f(1) FROM t",
            "trino" to "WITH FUNCTION my_f(x bigint) RETURNS bigint RETURN x SELECT my_f(1)",
            "trino" to "SELECT * FROM TABLE(exclude_columns(input => TABLE(t), columns => DESCRIPTOR(a)))",
            "doris" to "SELECT PARSE_JSON('{}'), my_udf(a) FROM t",
            "doris" to "SELECT /*+ SET_VAR(query_timeout = 10) */ abs(1) FROM t",
            "doris" to "SELECT * FROM numbers(\"number\" = \"3\")",
        )) assertReady("EXPLAIN $sql", sql, dialect)
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
    fun statementsInsideAQueryAreRefused() {
        assertRefused("WITH d AS (DELETE FROM t RETURNING x) SELECT * FROM d")
        assertRefused("WITH i AS (INSERT INTO t VALUES (1) RETURNING x) SELECT * FROM i")
        assertTrue("INTO" in assertRefused("SELECT x INTO t2 FROM t"))
    }

    /** Parameters would reach the server unprompted (a syntax error there): they are resolved with Execute. */
    @Test
    fun parametersAreRefused() {
        for (sql in listOf("SELECT * FROM t WHERE x = ?", "SELECT * FROM t WHERE x = :p", "SELECT * FROM t WHERE x = \${p}", "SELECT * FROM t WHERE x = #{p}")) {
            assertRefused(sql)
        }
        assertRefused("SELECT * FROM t WHERE x = ?", "doris")
    }

    /** `EXPLAIN (` opens Trino's options list, so a query starting with a parenthesis is explained as a derived table. */
    @Test
    fun parenthesizedQueriesAreWrapped() {
        assertReady("EXPLAIN SELECT * FROM ((SELECT 1)\n) AS explained", "(SELECT 1)")
        assertReady("EXPLAIN SELECT * FROM ((SELECT 1) UNION (SELECT 2)\n) AS explained", "(SELECT 1) UNION (SELECT 2)", "doris")
        assertReady("EXPLAIN SELECT * FROM (/* c */ -- d\n(SELECT 1)\n) AS explained", "/* c */ -- d\n(SELECT 1)")
        assertReady("EXPLAIN SELECT * FROM ((SELECT 1) -- trailing\n) AS explained", "(SELECT 1) -- trailing")
    }

    /** Text core cannot parse is the server's to judge when it starts like a query and holds no statement keyword. */
    @Test
    fun unreadableQueriesGoToTheServer() {
        assertReady("EXPLAIN SELECT FROM WHERE = 1", "SELECT FROM WHERE = 1")
        assertRefused("CREATE TABLE t AS SELECT FROM WHERE = 1")
        assertRefused("SELECT x INTO t2 FROM WHERE (")
        assertRefused("WITH x AS (SELECT 1) INSERT INTO t SELECT FROM WHERE = 1")
    }

    @Test
    fun scriptsAndPipesAreRefused() {
        assertRefused("SELECT 1; SELECT 2")
        assertRefused("")
        assertRefused("FROM t |> WHERE a > 1")
        assertRefused("SELECT 'unterminated")
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
