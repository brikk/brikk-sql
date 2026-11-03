package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.SqlglotError
import dev.brikk.house.sql.optimizer.lineageAll
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.optimizer.qualifyColumns
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckdbKeyedRecursiveTest {
    private val facts = RelationFacts(catalog = CatalogKnowledge.CLOSED)
    private val average = "WITH RECURSIVE t(a,b) USING KEY(a,avg(b)) AS (SELECT 1,5 UNION ALL SELECT a,b-1 FROM t WHERE b>0) SELECT * FROM t"
    private val recurring = "WITH RECURSIVE t(a,b,c) USING KEY(a,avg(b),list(c)) AS (SELECT 1,3,NULL::DOUBLE UNION ALL SELECT n.a,n.b-1,r.b FROM t n,recurring.t r WHERE n.b>0) SELECT * FROM t"

    @Test
    fun aggregatesAliasesExpressionsAndMaterializationHintsSurviveCopySerdeAndGeneration() {
        for (sql in listOf(average, recurring,
            "WITH RECURSIVE t(k,v) USING KEY(k,min(abs(v)) AS v) AS (SELECT 1,-5 UNION SELECT k+1,-v FROM t WHERE k<2) SELECT * FROM t",
            average.replace("avg(b)", "string_agg(':literal') AS b"),
            average.replace(" AS (SELECT", " AS MATERIALIZED (SELECT"),
            average.replace(" AS (SELECT", " AS NOT MATERIALIZED (SELECT"))) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree, tree.copy())
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val rendered = tree.sql("duckdb")
            assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
        }
        val cte = Dialects.DUCKDB.parseOne(average).findAll<CTE>().single()
        val keys = cte.args["key_expressions"] as List<*>
        assertIs<Identifier>(keys[0])
        assertIs<Avg>(keys[1])
        assertEquals(false, (cte.thisArg as Union).args["distinct"])
    }

    @Test
    fun finalizedOutputsHaveAggregateTypesAndComputedRatherThanSeedProvenance() {
        for ((sql, expected) in listOf(average to listOf("INT", "DOUBLE"), recurring to listOf("INT", "DOUBLE", "ARRAY<DOUBLE>"),
            average.replace("avg(b)", "sum(b)") to listOf("INT", "INT128"),
            average.replace("avg(b)", "count(b) AS b") to listOf("INT", "BIGINT"))) {
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
            val columns = scope.output!!.columns
            assertEquals(expected, columns.map { it.sqlType }, sql)
            assertTrue(columns.all { it.origin == ColumnOrigin.COMPUTED && it.provenance == null })
            assertEquals(expected, SqlFragment(sql, "duckdb").outputShape().columns.map { it.type }, sql)
        }
    }

    @Test
    fun recursiveAndRecurringFramesUseTheirOwnStageTypes() {
        val all = RelationScope.analyze(recurring, "duckdb", facts)
        assertEquals(setOf("INT"), all.references.filter { it.name == "b" && it.columns.any { c -> c.relation == "n" } }.flatMap { it.columns }.map { it.sqlType }.toSet())
        assertEquals(setOf("DOUBLE"), all.references.filter { it.name == "b" && it.columns.any { c -> c.relation == "r" } }.flatMap { it.columns }.map { it.sqlType }.toSet())
        val union = "WITH RECURSIVE t(k,total) USING KEY(k,sum(total)) AS (SELECT 1,1::INT UNION SELECT k,1 FROM t WHERE total<3) SELECT * FROM t"
        val scope = RelationScope.analyze(union, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        assertTrue(scope.references.any { it.name == "total" && it.columns.any { c -> c.relation == "t" && c.sqlType == "INT128" } })
        assertEquals(emptyList(), SqlFragment(recurring, "duckdb").sourceTables)
        val physical = recurring.replace("SELECT 1,3,NULL::DOUBLE", "SELECT id,score,amount FROM seeds")
        assertEquals(listOf("seeds"), SqlFragment(physical, "duckdb").sourceTables)
    }

    @Test
    fun usingKeyAndRecursionCompletionSeeCteAliasesNotAnchorProjectionNames() {
        fun names(sql: String): List<String> {
            val caret = sql.indexOf("<caret>")
            return RelationScope.complete(sql.replace("<caret>", ""), "duckdb", facts, caret).items.map { it.name }
        }
        assertEquals(listOf("a", "b"), names(average.replace("avg(b)", "avg(<caret>)")))
        assertEquals(listOf("a", "b", "c"), names(recurring.replace("r.b FROM", "r.<caret> FROM")))
        val invalid = RelationScope.analyze(average.replace("avg(b)", "avg(missing) AS b"), "duckdb", facts)
        assertTrue(invalid.diagnostics.any { it.problem == ScopeProblem.MISSING_COLUMN })
    }

    @Test
    fun valuesAnchorsAndAliasedDestinationsKeepFinalNamesWithoutGuessingUnknownAggregateTypes() {
        val sql = "WITH RECURSIVE t(k,v) USING KEY(k,avg(v)) AS (VALUES (1,0),(1,2) UNION SELECT k,x FROM t,(VALUES(0),(2)) r(x)) SELECT * FROM t"
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        assertEquals(listOf("k" to "INT", "v" to "DOUBLE"), scope.output!!.columns.map { it.name to it.sqlType })
        val aliased = "WITH RECURSIVE t(k,b,total) USING KEY(k,sum(b) AS total) AS (SELECT 1,3,0 UNION ALL SELECT k,b-1,0 FROM t WHERE b>0) SELECT * FROM t"
        assertEquals(listOf("INT", "INT", "INT128"), RelationScope.analyze(aliased, "duckdb", facts).output!!.columns.map { it.sqlType })
        val unknown = RelationScope.analyze(average.replace("avg(b)", "custom_aggregate(b) AS b"), "duckdb", facts)
        assertNull(unknown.output!!.columns.last().sqlType)
        assertFailsWith<ParseError> { Dialects.DUCKDB.parseOne(average.replace("a,avg(b)", "")) }
        val outside = average.replace("SELECT * FROM t", "SELECT * FROM recurring.t")
        assertTrue(RelationScope.analyze(outside, "duckdb", facts).diagnostics.any { it.problem == ScopeProblem.UNKNOWN_TABLE })
        assertEquals(listOf("recurring.t"), SqlFragment(outside, "duckdb").sourceTables)
        assertEquals(listOf("\"recurring.t\""), SqlFragment(average.replace("SELECT * FROM t", "SELECT * FROM \"recurring.t\""), "duckdb").sourceTables)
    }

    @Test
    fun selectOptimizerLineageAndCrossTargetExecutionRefuseUnmodeledRecursiveStateRewrites() {
        val tree = Dialects.DUCKDB.parseOne(recurring)
        val before = Serde.dump(tree)
        assertFailsWith<OptimizeError> { qualify(tree, dialect = Dialects.DUCKDB) }
        assertEquals(before, Serde.dump(tree))
        assertFailsWith<OptimizeError> { qualifyColumns(tree, schema = emptyMap<String, String>(), dialect = Dialects.DUCKDB) }
        assertFailsWith<OptimizeError> { annotateTypes(tree, dialect = Dialects.DUCKDB) }
        assertEquals(before, Serde.dump(tree))
        assertFailsWith<SqlglotError> { lineageAll(recurring, dialect = Dialects.DUCKDB) }
        assertFailsWith<UnsupportedError> { SqlFragment(recurring, "duckdb").toStandardSql(inputs = ShapeCatalog.EMPTY, expandStars = true) }
        for (target in listOf("trino", "postgres", "doris", "clickhouse")) {
            assertFailsWith<UnsupportedError>(target) { SqlFragment(recurring, "duckdb").toExecutable(target) }
            assertTrue(SqlFragment(recurring, "duckdb").transpileTo(target).unsupportedMessages.any { "USING KEY" in it })
        }
        assertTrue(SqlFragment(average, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        val ordinary = "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM t WHERE n<4) SELECT n FROM t"
        assertEquals(listOf("INT"), SqlFragment(ordinary, "duckdb").outputShape().columns.map { it.type })
    }
}
