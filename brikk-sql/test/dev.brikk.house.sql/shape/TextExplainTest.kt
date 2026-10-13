package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextExplainTest {
    private val builtins = setOf("known_builtin", "abs", "lower", "upper", "count", "coalesce", "date_add", "max", "explode")

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

    /** Trino lets an inline function take a built-in's name (and, from 455, run Python): no routine declaration is explained. */
    @Test
    fun routineDeclarationsAreRefused() {
        assertTrue("routine" in assertRefused("WITH FUNCTION abs(x bigint) RETURNS bigint RETURN x + 1 SELECT abs(1)"))
        assertTrue("routine" in assertRefused("WITH FUNCTION my_f(x bigint) RETURNS bigint RETURN x SELECT 1"))
    }

    /** Every function call is checked by the name as written, also where the parser builds a typed node for it. */
    @Test
    fun functionsAreCheckedByTheirWrittenNames() {
        assertTrue("sequence" in assertRefused("SELECT sequence(1, 3)"))
        assertTrue("generate_series" in assertRefused("SELECT * FROM t WHERE x IN (SELECT generate_series(1, 2))"))
        assertTrue("explode_split" in assertRefused("SELECT x FROM t LATERAL VIEW explode_split(a, ',') v AS x", "doris"))
        assertReady("EXPLAIN SELECT abs(-1), \"lower\"(s), count(*), COALESCE(a, b), date_add('day', 1, d) FROM t", "SELECT abs(-1), \"lower\"(s), count(*), COALESCE(a, b), date_add('day', 1, d) FROM t")
        assertReady("EXPLAIN SELECT `upper`(s), CAST(x AS INT) FROM t", "SELECT `upper`(s), CAST(x AS INT) FROM t", "doris")
        // Grammar forms have no function name to check.
        assertReady("EXPLAIN SELECT CAST(x AS integer), EXTRACT(YEAR FROM d), CASE WHEN a THEN 1 END, CURRENT_DATE FROM t", "SELECT CAST(x AS integer), EXTRACT(YEAR FROM d), CASE WHEN a THEN 1 END, CURRENT_DATE FROM t")
    }

    @Test
    fun statementsInsideAQueryAreRefused() {
        assertRefused("WITH d AS (DELETE FROM t RETURNING x) SELECT * FROM d")
        assertRefused("WITH i AS (INSERT INTO t VALUES (1) RETURNING x) SELECT * FROM i")
        assertTrue("INTO" in assertRefused("SELECT x INTO t2 FROM t"))
    }

    /** Parameters would reach the server unprompted: they are resolved with Execute, as for PIPE. */
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
        assertReady("EXPLAIN SELECT * FROM ((SELECT 1)) AS explained", "(SELECT 1)")
        assertReady("EXPLAIN SELECT * FROM ((SELECT 1) UNION (SELECT 2)) AS explained", "(SELECT 1) UNION (SELECT 2)", "doris")
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
