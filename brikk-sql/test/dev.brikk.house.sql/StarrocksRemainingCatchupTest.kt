package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class StarrocksRemainingCatchupTest {
    @Test fun nativeDateFunctionsKeepDatePartVersusElapsedSemantics() {
        assertIs<ToDays>(parseOne("TO_DAYS('2026-03-10')","starrocks"))
        val diff = assertIs<DateDiff>(parseOne("DATEDIFF(e,s)","starrocks"))
        assertEquals(true,diff.args["date_part_boundary"])
        assertEquals("DATEDIFF(e, s)",diff.sql("starrocks"))
        assertEquals("DATE_DIFF('DAY', e, s)",parseOne("DATE_DIFF('day',e,s)","starrocks").sql("starrocks"))
        assertEquals("TO_DAYS(NULL)",parseOne("TO_DAYS(NULL)","starrocks").sql("starrocks"))
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT TO_DAYS(d)","starrocks").toExecutable("duckdb") }
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT DATE_DIFF(DATE '2024-02-01',DATE '2024-01-31',MONTH)","bigquery").toExecutable("starrocks") }
    }
    @Test fun bareHashesAreRejectedWithoutBreakingQuotedHashesOrMysqlComments() {
        for (sql in listOf("SELECT 1#2","SELECT 1 AS a # ,2 AS b","SELECT 3 #> 2","SELECT 3 #>> 2")) {
            assertFailsWith<ParseError>(sql) { parseOne(sql,"starrocks") }
        }
        for (sql in listOf("SELECT 'a#b' AS c","SELECT 1 AS `a#b`","SELECT 1 -- tail")) parseOne(sql,"starrocks")
        parseOne("SELECT 1 # MySQL comment","mysql")
    }
    @Test fun pivotIsRenderedAndItsStaticNamesUseStarrocksRules() {
        val source = "SELECT * FROM t PIVOT(SUM(v) AS s, MAX(v) AS m FOR k IN ('a', 'b'))"
        val tree = parseOne(source,"starrocks"); val pivot = tree.findAll<Pivot>().single()
        assertEquals(listOf("'a'_s","'a'_m","'b'_s","'b'_m"),(pivot.args["columns"] as List<*>).filterIsInstance<Expression>().map { it.name })
        assertContains(tree.sql("starrocks"),"PIVOT")
        val unknown = parseOne("SELECT * FROM t PIVOT(SUM(v), MAX(v) FOR k IN (1,2))","starrocks").findAll<Pivot>().single()
        assertEquals(emptyList<Expression>(),unknown.args["columns"])
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT * FROM t UNPIVOT(v FOR k IN (a,b))","starrocks").toExecutable("starrocks") }
        val tuple = parseOne("SELECT * FROM t PIVOT(SUM(v) FOR (a,b) IN ((1,2)))","starrocks").findAll<Pivot>().single()
        assertEquals("{1,2}",(tuple.args["columns"] as List<*>).filterIsInstance<Expression>().single().name)
    }
    @Test fun aggregateAndHllDdlAreStructuredAndPreserveConstraints() {
        for (agg in listOf("SUM","MAX","MIN","REPLACE","REPLACE_IF_NOT_NULL","HLL_UNION")) {
            val type = if (agg=="HLL_UNION") "HLL" else "INT"
            val sql = "CREATE TABLE t (k INT, v $type $agg) AGGREGATE KEY (k) DISTRIBUTED BY HASH (k)"
            val tree = assertIs<Create>(parseOne(sql,"starrocks"))
            assertNotNull(tree.find<AggregateKeyProperty>())
            assertNotNull(tree.find<AggregateTypeColumnConstraint>())
            val before=Serde.dump(tree); val output=Dialects.STARROCKS.generator(sourceDialect="starrocks").generate(tree,copy=false)
            assertEquals(sql,output); assertEquals(before,Serde.dump(tree)); assertEquals(tree,Serde.loadExpression(before))
            assertFailsWith<UnsupportedError> { SqlFragment(sql,"starrocks").toExecutable("postgres") }
        }
    }
    @Test fun refreshOptionsAreStructuredAndUnknownCommandsKeepTheWholeStatement() {
        val sql="REFRESH MATERIALIZED VIEW db.mv PARTITION START ('2020-02-01') END ('2020-03-01') FORCE WITH SYNC MODE"
        val tree=assertIs<Refresh>(parseOne(sql,"starrocks")); assertEquals(true,tree.args["force"])
        assertNotNull(tree.args["partition_start"]); assertNotNull(tree.args["partition_end"])
        assertEquals("SYNC",tree.args["mode"]); assertEquals(sql,tree.sql("starrocks"));assertEquals(tree,Serde.loadExpression(Serde.dump(tree)))
        assertFailsWith<UnsupportedError> { SqlFragment(sql,"starrocks").toExecutable("postgres") }
        for (tail in listOf("WITH","MODE","WITH SYNC","WITH ASYNC")) assertFailsWith<ParseError> { parseOne("REFRESH MATERIALIZED VIEW mv $tail","starrocks") }
        for (source in listOf("REFRESH DICTIONARY dict_obj","REFRESH CONNECTIONS FORCE")) {
            val opaque=assertIs<Command>(parseOne(source,"starrocks"));assertEquals(source,opaque.sql("starrocks"))
        }
    }
    @Test fun administrationIsOpaquePreservationNotStructuredOrCrossTargetSupport() {
        for (sql in listOf("ADMIN SHOW FRONTEND CONFIG LIKE 'disable_balance'","BACKUP SNAPSHOT db.s TO repo ON (t)",
            "CANCEL LOAD FROM db WHERE LABEL = 'x'","PAUSE ROUTINE LOAD FOR db.job","RESUME ROUTINE LOAD FOR db.job",
            "STOP ROUTINE LOAD FOR db.job","DEALLOCATE PREPARE stmt1","INSTALL PLUGIN FROM '/not-executed.zip'",
            "UNINSTALL PLUGIN p","ADD SQLBLACKLIST 'select .+'","DELETE BACKEND BLACKLIST 10001",
            "TRANSLATE TRINO SELECT id FROM products")) {
            val tree=assertIs<Command>(parseOne(sql,"starrocks"));assertEquals(sql,tree.sql("starrocks"))
            assertFailsWith<UnsupportedError> { SqlFragment(sql,"starrocks").toExecutable("postgres") }
        }
        assertIs<Delete>(parseOne("DELETE FROM t WHERE a=1","starrocks"))
        parseOne("ALTER TABLE t ADD COLUMN c INT","starrocks")
        assertTrue(parseOne("SELECT admin,cancel,export,stop,TRANSLATE(c,'a','b'),translate FROM t","starrocks").findAll<Command>().none())
    }
    @Test fun multiArrayUnnestKeepsAllArgumentsWithoutInventingDistinctDefaultNames() {
        val tree=parseOne("SELECT * FROM TABLE(UNNEST([1,2],['a','b'])) AS t(x,y)","starrocks")
        assertEquals(2,tree.findAll<Unnest>().single().expressionsArg.size)
        val direct=parseOne("SELECT * FROM UNNEST([1,2],['a','b']) AS t","starrocks").findAll<Unnest>().single()
        assertTrue(direct.aliasColumnNames.isEmpty())
        assertEquals(listOf("unnest"),parseOne("SELECT * FROM UNNEST([1,2])","starrocks").findAll<Unnest>().single().aliasColumnNames)
    }
    @Test fun knownPivotShapesAreStaticAndUnknownAggregateNamesStayUnknown() {
        val source="SELECT * FROM (SELECT 1 AS g,'a' AS k,10 AS v) AS t PIVOT(MAX(v) FOR k IN ('a'))"
        val shape=SqlFragment(source,"starrocks").outputShape()
        assertEquals(listOf("g","'a'"),shape.columns.map { it.name })
        assertEquals(listOf("INT","INT"),shape.columns.map { it.type })
        val unknown=parseOne(source.replace("MAX(v)","SUM(v), MAX(v)"),"starrocks")
        val qualified=dev.brikk.house.sql.optimizer.qualify(unknown,dialect=Dialects.STARROCKS,validateQualifyColumns=false)
        assertTrue(qualified.findAll<Star>().any())
    }
}
