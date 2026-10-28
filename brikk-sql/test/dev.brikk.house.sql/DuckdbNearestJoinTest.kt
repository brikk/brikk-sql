package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Add
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Nearest
import dev.brikk.house.sql.ast.Mul
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.ReferenceKind
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.Resolution
import dev.brikk.house.sql.scope.ScopeProblem
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckdbNearestJoinTest {
    private fun column(name: String, type: String = "INT") = ColumnFact(name, type, nullability = Nullability.NON_NULL, provenance = name)
    private val facts = RelationFacts(tables = mapOf(
        "queries" to RelationFact(listOf(column("id"), column("x"), column("vec", "FLOAT[2]"))),
        "points" to RelationFact(listOf(column("id"), column("y"), column("vec", "FLOAT[2]"), column("weight"))),
    ), catalog = CatalogKnowledge.CLOSED)

    @Test
    fun modesCountsAliasesAndMetricPrecedenceSurviveCopyAndSerde() {
        for (sql in listOf(
            "SELECT q.id, p.id FROM queries AS q INNER JOIN points AS p APPROX NEAREST 2 BY SIMILARITY ARRAY_COSINE_SIMILARITY(q.vec, p.vec)",
            "SELECT q.id, p.id FROM queries AS q LEFT OUTER JOIN points AS p EXACT NEAREST 1 BY DISTANCE ARRAY_DISTANCE(q.vec, p.vec)",
            "SELECT q.id, points.id FROM queries AS q JOIN points NEAREST BY DISTANCE ABS(q.x - points.y)",
            "SELECT q.id, p.id FROM queries AS q JOIN (SELECT * FROM points WHERE y > 0) AS p NEAREST 2 BY DISTANCE ABS(q.x - p.y) WHERE q.id > 0 ORDER BY p.id",
            "SELECT * FROM (SELECT 1 AS x) AS q JOIN (VALUES (10), (20)) NEAREST 1 BY DISTANCE ABS(col0 - q.x)",
            "SELECT * FROM (SELECT 1 AS x) AS q JOIN RANGE(3) NEAREST BY DISTANCE ABS(range - q.x)",
            "SELECT q.id, p.id FROM queries AS q JOIN points AS p NEAREST 2 BY DISTANCE ABS(q.x - p.y) + 2 * p.weight JOIN points AS r ON r.id = p.id",
        )) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"), sql)
            assertEquals(tree, tree.copy(), sql)
            assertEquals(tree, Serde.load(Serde.dump(tree)), sql)
            assertEquals(1, tree.findAll<Nearest>().count(), sql)
        }
        val metric = Dialects.DUCKDB.parseOne("SELECT * FROM queries q JOIN points p NEAREST 2 BY DISTANCE ABS(q.x - p.y) + 2 * p.weight").findAll<Nearest>().single()
        val score = assertIs<Add>(metric.thisArg)
        assertIs<Mul>(score.expressionArg)
        assertEquals("2", (metric.args["count"] as Expression).name)
        assertEquals(setOf("q", "p"), score.findAll<Column>().map { it.table }.toSet())
    }

    @Test
    fun quotedModeWordAliasesAreNotConsumedAsApproxOrExactHints() {
        for (alias in listOf("exact", "approx", "nearest")) {
            val sql = "SELECT q.id, \"$alias\".id FROM queries q JOIN points \"$alias\" NEAREST 1 BY DISTANCE ABS(q.x - \"$alias\".y)"
            val tree = Dialects.DUCKDB.parseOne(sql)
            val join = tree.findAll<Join>().single()
            assertEquals(alias, (join.thisArg as Table).alias, sql)
            val nearest = join.args["nearest"] as Nearest
            assertFalse(nearest.args["approximate"] == true, sql)
            assertFalse(nearest.args["exact"] == true, sql)
            assertTrue(tree.sql("duckdb").contains("AS \"$alias\" NEAREST 1"), tree.sql("duckdb"))
        }
    }

    @Test
    fun nativeInvalidCountsJoinKindsAndExtraQualifiersAreNotAccepted() {
        val rank = "BY DISTANCE ABS(q.x - p.y)"
        for (count in listOf("0", "-1", "1.5", "1.0", "1e0", "9223372036854775808", "?", "\$1")) {
            val sql = "SELECT * FROM queries q JOIN points p NEAREST $count $rank"
            assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
        }
        for ((count, expected) in listOf("01" to 1L, "1_0" to 10L, "9223372036854775807" to Long.MAX_VALUE)) {
            val tree = Dialects.DUCKDB.parseOne("SELECT * FROM queries q JOIN points p NEAREST $count $rank")
            assertEquals(expected, (tree.findAll<Nearest>().single().args["count"] as Expression).name.toLong())
        }
        for (kind in listOf("RIGHT", "FULL OUTER", "SEMI", "ANTI", "CROSS", "NATURAL", "ASOF LEFT", "LEFT INNER")) {
            val sql = "SELECT * FROM queries q $kind JOIN points p NEAREST 1 $rank"
            assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
        }
        for (sql in listOf(
            "SELECT * FROM queries q, points p NEAREST 1 $rank",
            "SELECT * FROM queries q JOIN points p ON q.id = p.id NEAREST 1 $rank",
            "SELECT * FROM queries q JOIN points p USING (id) NEAREST 1 $rank",
            "SELECT * FROM queries q JOIN points p NEAREST 1 BY \"DISTANCE\" ABS(q.x - p.y)",
        )) assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
    }

    @Test
    fun nearestWordsRemainUsableAsOrdinaryIdentifiersAndAliases() {
        for (sql in listOf(
            "SELECT approx, exact, distance, similarity FROM nearest ORDER BY nearest.approx",
            "SELECT 5 AS nearest, 6 AS approx, 7 AS exact",
            "SELECT nearest.id FROM points AS nearest JOIN queries AS exact ON nearest.id = exact.id",
        )) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertNull(tree.find(Nearest::class), sql)
            assertEquals(tree.sql("duckdb"), Dialects.DUCKDB.parseOne(tree.sql("duckdb")).sql("duckdb"), sql)
        }
    }

    @Test
    fun rankingExpressionsResolveBeforeLeftJoinNullExtensionAndCompleteTheCorrectAlias() {
        val sql = "SELECT '😀' AS tag, q.id AS query_id, p.id AS point_id FROM queries q LEFT JOIN points p NEAREST 2 BY DISTANCE ABS(q.x - p.y) + p.weight"
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        for (written in listOf("q.x", "p.y", "p.weight")) {
            val start = sql.indexOf(written) + written.indexOf('.') + 1
            val reference = scope.references.single { it.kind == ReferenceKind.COLUMN && it.start == start }
            assertEquals(Resolution.RESOLVED, reference.resolution, written)
            assertEquals(Nullability.NON_NULL, reference.columns.single().nullability, written)
            assertEquals(written.substringAfter('.'), sql.substring(reference.start, reference.end))
        }
        assertEquals(listOf(Nullability.NON_NULL, Nullability.NULLABLE), scope.output!!.columns.drop(1).map { it.nullability })
        val missing = RelationScope.analyze(sql.replace("p.weight", "p.missing"), "duckdb", facts)
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), missing.diagnostics.map { it.problem })
        val completing = "SELECT * FROM queries q LEFT JOIN points p NEAREST 2 BY DISTANCE ARRAY_DISTANCE(q.vec, p.)"
        val caret = completing.indexOf("p.)") + 2
        assertEquals(listOf("id", "y", "vec", "weight"), RelationScope.complete(completing, "duckdb", facts, caret).items.map { it.name })
    }

    @Test
    fun shapeQualificationAndLineageKeepTheNearestJoinAndBothPhysicalSources() {
        val catalog = ShapeCatalog(facts.tables.mapValues { (_, table) -> Shape(table.columns.map { ColumnShape(it.name, it.sqlType!!, false) }) })
        val sql = "SELECT q.id AS query_id, p.id AS point_id FROM queries q LEFT JOIN points p NEAREST 1 BY DISTANCE ABS(q.x - p.y)"
        val fragment = SqlFragment(sql, "duckdb")
        val shape = fragment.outputShape(catalog)
        assertEquals(listOf("query_id", "point_id"), shape.names())
        assertEquals(listOf(false, true), shape.columns.map { it.nullable })
        assertEquals(mapOf("query_id" to setOf("queries"), "point_id" to setOf("points")), fragment.columnDependencies(inputs = catalog))
        val qualified = fragment.toStandardSql("duckdb", inputs = catalog, expandStars = true)
        assertTrue(qualified.contains("LEFT JOIN") && qualified.contains("NEAREST 1 BY DISTANCE"), qualified)
        assertEquals(sql, fragment.sql)
    }

    @Test
    fun executableCrossTargetGenerationRefusesNearestRatherThanDroppingIt() {
        val fragment = SqlFragment("SELECT q.id, p.id FROM queries q JOIN points p NEAREST 1 BY DISTANCE ABS(q.x - p.y)", "duckdb")
        for (target in listOf("postgres", "trino", "doris", "clickhouse", "mysql", "hive", "spark", "bigquery", "datafusion", "starrocks", "presto")) {
            assertFailsWith<UnsupportedError>(target) { fragment.toExecutable(target) }
            assertTrue(fragment.transpileTo(target).unsupportedMessages.any { "NEAREST" in it }, target)
        }
    }
}
