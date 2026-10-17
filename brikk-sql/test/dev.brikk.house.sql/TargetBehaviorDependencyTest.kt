package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TargetBehaviorDependencyTest {
    @Test
    fun preservedFunctionNamesAreDialectScopedAndLegacyNativeDumpsStillRender() {
        val tree = Dialects.CLICKHOUSE.parseOne("SELECT exp(1)")
        val value = tree.selects.single()
        assertEquals("exp", value.meta["clickhouse_name"])
        assertEquals(null, value.meta["name"])
        assertEquals("SELECT EXP(1)", tree.sql("bigquery"))
        assertEquals("SELECT exp(1)", tree.sql("clickhouse"))
        val legacy = Serde.load(Serde.dump(tree)) as Expression
        legacy.selects.single().meta.remove("clickhouse_name")
        legacy.selects.single().meta["name"] = "exp"
        assertEquals("SELECT exp(1)", legacy.sql("clickhouse"))
    }

    @Test
    fun singularRowAndCountlessFetchRemainPaginationNotIdentifiers() {
        for (source in listOf("SELECT * FROM t ORDER BY x FETCH FIRST 1 ROW ONLY",
            "SELECT * FROM t ORDER BY x OFFSET 1 ROW FETCH NEXT 1 ROW WITH TIES", "SELECT * FROM t FETCH FIRST ROW ONLY")) {
            val tree = Dialects.TRINO.parseOne(source)
            assertIs<Fetch>(tree.args["limit"])
            assertEquals(tree, Dialects.TRINO.parseOne(tree.sql("trino")))
        }
    }

    @Test
    fun searchAndCycleDefaultsAndBooleanAssignmentsRetainSeparateAstRoles() {
        val sql = "WITH RECURSIVE q(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM q WHERE n<3) " +
            "SEARCH DEPTH FIRST BY n SET s CYCLE n SET flag TO 'Y' DEFAULT 'N' USING path SELECT n FROM q"
        val tree = Dialects.POSTGRES.parseOne(sql)
        val with = tree.args["with_"] as With
        assertIs<RecursiveWithSearch>(with.args["search"])
        assertIs<RecursiveWithSearch>(with.args["cycle"])
        assertEquals(tree, Dialects.POSTGRES.parseOne(tree.sql("postgres")))
        for (source in listOf("UPDATE t SET a=x OR y,b=x AND y",
            "INSERT INTO t(a) VALUES(1) ON CONFLICT(a) DO UPDATE SET a=x OR y")) {
            val statement = Dialects.POSTGRES.parseOne(source)
            assertTrue(statement.findAll<EQ>().any { it.expressionArg is Or })
            assertEquals(statement, Serde.load(Serde.dump(statement)))
        }
    }

    @Test
    fun nullOrderAliasSimulationStopsAtAggregationAndSetOperationBoundaries() {
        for (source in listOf("SELECT ARRAY_AGG(v ORDER BY v NULLS LAST) AS v FROM t",
            "SELECT x AS v,ARRAY_AGG(v ORDER BY v NULLS LAST) AS w FROM t GROUP BY x")) {
            val tree = Dialects.TRINO.parseOne(source)
            val before = tree.copy()
            val output = tree.sql("mysql")
            assertTrue("CASE WHEN v IS NULL" in output, output)
            assertEquals(before, tree)
        }
        val set = Dialects.POSTGRES.parseOne("WITH c AS (SELECT a AS v,b AS x FROM t UNION ALL SELECT a,b FROM u " +
            "ORDER BY v NULLS LAST LIMIT 1) SELECT x AS v FROM c").sql("mysql")
        assertTrue("CASE WHEN v IS NULL" in set, set)
    }

    @Test
    fun semiJoinConditionsAndDuplicateAliasOrdinalsUseTheirOwnScopes() {
        val schema = mapOf("w" to mapOf("d" to "INT"), "y" to mapOf("b" to "INT"), "z" to mapOf("b" to "INT"))
        for (policy in listOf(true, false, null)) {
            val tree = qualify(Dialects.DUCKDB.parseOne("SELECT w.d FROM w SEMI JOIN y ON b>0 JOIN z ON TRUE"),
                dialect = Dialects.DUCKDB, schema = schema, inferSchema = policy)
            assertEquals("y", tree.findAll<Column>().single { it.name == "b" }.table)
            assertFailsWith<OptimizeError> {
                qualify(Dialects.DUCKDB.parseOne("SELECT w.d FROM w JOIN y ON TRUE SEMI JOIN z ON b>0"),
                    dialect = Dialects.DUCKDB, schema = schema, inferSchema = policy)
            }
        }
        val tree = qualify(Dialects.DUCKDB.parseOne("SELECT a AS j,-b AS j FROM x GROUP BY 1,2 ORDER BY 1,-b"),
            dialect = Dialects.DUCKDB, schema = mapOf("x" to mapOf("a" to "INT", "b" to "INT")))
        assertTrue("ORDER BY 1," in tree.sql("duckdb"))
    }

    @Test
    fun fromFirstInsertAndTypedJsonIntentSurviveWithoutAddingOptimizerFeatures() {
        for (dialect in listOf(Dialects.HIVE, Dialects.SPARK)) {
            val source = "WITH c AS (SELECT 1 AS k) FROM c INSERT OVERWRITE TABLE a SELECT k INSERT INTO b SELECT k"
            val tree = assertIs<MultitableInserts>(dialect.parseOne(source))
            assertEquals(2, tree.expressionsArg.size)
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            assertIs<MultitableInserts>(dialect.parseOne(tree.sql(dialect.name)))
            val target = Dialects.POSTGRES.generator()
            target.generate(tree)
            assertTrue(target.unsupportedMessages.any { "FROM-first" in it })
        }
        assertEquals("JSON '\"foo\"'", Dialects.PRESTO.parseOne("JSON '\"foo\"'").sql("bigquery"))
        val lateral = Dialects.POSTGRES.parseOne("SELECT r.v,r.idx FROM t CROSS JOIN LATERAL UNNEST(t.a) WITH ORDINALITY AS r(v,idx)")
        assertEquals(listOf("v", "idx"), lateral.findAll<Lateral>().single().namedSelects)
        assertTrue("WITH ORDINALITY AS r(v, idx)" in lateral.sql("postgres"))
    }
}
