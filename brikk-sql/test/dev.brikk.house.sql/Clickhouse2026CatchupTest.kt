package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.buildScope
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Clickhouse2026CatchupTest {
    private fun roundTrip(sql: String): Expression {
        val ast = parseOne(sql, "clickhouse")
        val rendered = ast.sql("clickhouse")
        assertEquals(ast, parseOne(rendered, "clickhouse"), rendered)
        assertEquals(ast, Serde.load(Serde.dump(ast)))
        return ast
    }

    @Test
    fun streamIsATableModifierAndNeverAnImplicitAlias() {
        for (sql in listOf(
            "SELECT x FROM t STREAM",
            "SELECT a.x FROM db.t AS a STREAM BOUNDED UNORDERED WHERE a.x > 0 LIMIT 2",
            "SELECT a.x FROM db.t AS a FINAL SAMPLE 1 / 2 STREAM BOUNDED UNORDERED WHERE a.x > 0 LIMIT 2",
            "SELECT x FROM (SELECT x FROM t) AS a STREAM BOUNDED LIMIT 2",
            "SELECT a.x FROM t AS a STREAM BOUNDED JOIN u AS b ON a.x = b.x",
            "SELECT ts FROM t STREAM BOUNDED CURSOR {'shard': {'part': 2}, 'other': 0} " +
                "WATERMARK FOR ts AS ts - INTERVAL 1 SECOND IDLE TIMEOUT INTERVAL 2 SECOND LIMIT 2",
            "SELECT ts FROM t STREAM WATERMARK FOR ts AS ts",
            "SELECT x FROM t STREAM CURSOR {}",
        )) {
            val ast = roundTrip(sql)
            assertNotNull(ast.find<ClickhouseStream>(), sql)
            assertTrue("AS STREAM" !in ast.sql("clickhouse"), sql)
        }
    }

    @Test
    fun streamIdentifiersAndExplicitAliasesRemainAvailable() {
        for (sql in listOf(
            "SELECT stream FROM stream",
            "SELECT stream.x FROM t AS stream",
            "SELECT `stream`.x FROM t AS `stream`",
            "SELECT x FROM t `stream`",
        )) {
            val ast = roundTrip(sql)
            assertEquals(null, ast.find<ClickhouseStream>(), sql)
        }
        // Reserving a suffix must not turn a table/function named stream into a prefix modifier.
        roundTrip("SELECT * FROM stream(1)")
    }

    @Test
    fun malformedStreamClausesFailRatherThanDroppingOptions() {
        for (sql in listOf(
            "SELECT * FROM t STREAM CURSOR 'not an object'",
            "SELECT * FROM t STREAM CURSOR {'p': -1}",
            "SELECT * FROM t STREAM CURSOR {'p': 1.5}",
            "SELECT * FROM t STREAM CURSOR {'p': 18446744073709551616}",
            "SELECT * FROM t STREAM CURSOR {p: 1}",
            "SELECT * FROM t STREAM WATERMARK ts AS ts",
            "SELECT * FROM t STREAM WATERMARK FOR ts",
            "SELECT * FROM t STREAM WATERMARK FOR ts AS",
            "SELECT * FROM t STREAM WATERMARK FOR ts AS ts IDLE TIMEOUT INTERVAL -1 SECOND",
            "SELECT * FROM t STREAM WATERMARK FOR ts AS ts IDLE TIMEOUT INTERVAL '1' SECOND",
            "SELECT * FROM t STREAM WATERMARK FOR ts AS ts IDLE TIMEOUT INTERVAL 1",
            "SELECT * FROM t STREAM UNORDERED BOUNDED",
        )) assertFailsWith<ParseError>(sql) { parseOne(sql, "clickhouse") }
    }

    @Test
    fun tupleCombinatorsKeepParametersAndAggregateClassification() {
        val simple = roundTrip("SELECT sumTuple(t) FROM src")
        assertIs<CombinedAggFunc>(simple.expressionsArg.single())
        for (sql in listOf(
            "SELECT quantileTuple(0.5)(t) FROM src",
            "SELECT quantileExactTupleIf(0.5)(t, x > 0) FROM src",
            "SELECT quantileTuple(0.5)(t) OVER (PARTITION BY k) FROM src",
            "SELECT quantilesTuple(0.25, 0.75)((x, y)) FROM src",
        )) {
            val ast = roundTrip(sql)
            assertNotNull(ast.find<CombinedParameterizedAgg>(), sql)
            assertEquals(null, ast.find<Aliases>(), sql)
        }
        for (sql in listOf(
            "SELECT sumTupleIf(t, x > 0), sumTupleState(t) FROM src",
            "SELECT sumTuple(t) OVER (PARTITION BY k) FROM src",
            "SELECT corrTuple((x, y), (z, w)) FROM src",
            "SELECT sumTuple(CAST((x, y) AS Tuple(a UInt64, b UInt64))) FROM src",
        )) assertNotNull(roundTrip(sql).find<CombinedAggFunc>(), sql)
        // Existing parameter/suffix resolution must not change when Tuple joins the suffix set.
        assertEquals("SELECT quantileIf(0.5)(x, x > 0) FROM t",
            roundTrip("SELECT quantileIf(0.5)(x, x > 0) FROM t").sql("clickhouse"))
    }

    @Test
    fun streamAndTupleCombinatorsRefuseUnprovedCrossDialectTranslation() {
        for (sql in listOf("SELECT x FROM t STREAM BOUNDED", "SELECT sumTuple(t) FROM src",
            "SELECT quantileTuple(0.5)(t) FROM src")) {
            for (target in listOf("duckdb", "trino", "doris", "postgres")) {
                assertFailsWith<UnsupportedError>("$target: $sql") { SqlFragment(sql, "clickhouse").toExecutable(target) }
            }
        }
    }

    @Test
    fun streamingSourcesKeepAliasesColumnsAndWatermarkReferences() {
        val facts = RelationFacts(tables = mapOf("db.t" to RelationFact(listOf(
            ColumnFact("x", "UInt32"), ColumnFact("ts", "DateTime"),
        ))), catalog = CatalogKnowledge.CLOSED)
        val sql = "SELECT a.x FROM db.t AS a FINAL STREAM BOUNDED WATERMARK FOR ts AS ts - INTERVAL 1 SECOND"
        val scope = RelationScope.analyze(sql, "clickhouse", facts)
        assertTrue(scope.parsed)
        assertEquals(emptyList(), scope.diagnostics)
        assertEquals(listOf("x"), scope.output?.columns?.map { it.name })
        for (ref in scope.references.filter { it.kind == ReferenceKind.COLUMN }) {
            assertTrue(ref.columns.isNotEmpty(), ref.toString())
            assertEquals(ref.name, sql.substring(ref.start, ref.end))
        }
        assertEquals(2, scope.references.count { it.kind == ReferenceKind.COLUMN && it.name == "ts" })
        assertEquals(listOf("a"), buildScope(parseOne(sql, "clickhouse"))?.sources?.keys?.toList())
        val derived = "SELECT a.x FROM (SELECT x FROM db.t) AS a STREAM BOUNDED"
        assertEquals(listOf("x"), RelationScope.analyze(derived, "clickhouse", facts).output?.columns?.map { it.name })
    }

    @Test
    fun nativeAuthoredStreamingAndTupleSyntaxSurvivesExecutableLowering() {
        val sql = "select /* keep */ quantileTuple(0.5)(v) from db.t as a STREAM BOUNDED -- tail"
        assertEquals(sql, SqlFragment(sql, "clickhouse").toSourcePreservingExecutable().sql)
        val pipe = "FROM db.t AS a STREAM BOUNDED |> WHERE x > 0 |> SELECT x"
        val lowered = SqlFragment(pipe, "clickhouse").toExecutable("clickhouse").sql
        assertTrue("STREAM BOUNDED" in lowered, lowered)
        assertNotNull(parseOne(lowered, "clickhouse").find<ClickhouseStream>())
    }
}
