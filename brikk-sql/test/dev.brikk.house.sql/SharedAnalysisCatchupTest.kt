package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class SharedAnalysisCatchupTest {
    private fun types(sql: String, dialect: String = "duckdb"): List<String> =
        SqlFragment(sql, dialect).outputShape().columns.map { it.type }

    @Test fun valuesCoerceEveryRowNotOnlyTheFirst() {
        for (sql in listOf("SELECT x FROM (VALUES (NULL), (CAST(2.5 AS DOUBLE)), (1)) AS t(x)",
            "SELECT x FROM (VALUES (1), (NULL), (CAST(2.5 AS DOUBLE))) AS t(x)")) {
            assertEquals(listOf("DOUBLE"), types(sql))
        }
    }
    @Test fun nestedSetOperationsKeepPositionsAndSharedCteOutputs() {
        for (op in listOf("UNION ALL", "INTERSECT", "EXCEPT")) {
            assertEquals(listOf("DOUBLE"), types("SELECT t.a FROM (SELECT 1 AS a $op (SELECT 2 AS b $op SELECT CAST(2.5 AS DOUBLE) AS c)) AS t"))
        }
        assertEquals(listOf("INT", "DOUBLE"), types("SELECT t.a,t.b FROM (SELECT 1 AS a, 2 AS b UNION ALL SELECT 3 AS x, CAST(4.5 AS DOUBLE) AS x) AS t"))
        assertEquals(listOf("DOUBLE", "DOUBLE"), types("WITH t AS (SELECT 1 AS a UNION ALL SELECT CAST(2.5 AS DOUBLE)) SELECT x.a,y.a FROM t x CROSS JOIN t y"))
        assertEquals(listOf("DOUBLE"), types("WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT CAST(2.5 AS DOUBLE) UNION ALL SELECT n FROM t WHERE FALSE) SELECT n FROM t"))
    }
    @Test fun structSubqueriesAndRowReferencesNeedCompleteEvidence() {
        assertEquals(listOf("STRUCT<x INT, y VARCHAR>"), types("SELECT (SELECT AS STRUCT 1 AS x, 'a' AS y) AS r", "bigquery"))
        assertEquals(listOf("STRUCT<x DOUBLE>"), types("SELECT r FROM (SELECT 1 AS x UNION ALL SELECT CAST(2.5 AS DOUBLE) AS x) AS r"))
        val unknown = SqlFragment("SELECT r FROM (SELECT missing AS x) AS r", "duckdb").outputShape()
        assertEquals("UNKNOWN", unknown.columns.single().type)
    }
    @Test fun nestedUnknownCannotBecomeACompleteMap() {
        val tree = annotateTypes(parseOne("SELECT MAP(['a'], [missing]) AS m", "duckdb"), dialect = Dialects.DUCKDB)
        assertTrue((tree.find<MapNode>()!!.type as DataType).isType(DType.UNKNOWN))
        assertEquals(listOf("UNKNOWN"), types("SELECT APPROX_TOP_COUNT(missing,2) AS a", "bigquery"))
    }
    @Test fun structsAcrossCtesExpandAfterTheirSourcesAreKnown() {
        val inputs = ShapeCatalog(mapOf("t" to Shape.of("s" to "STRUCT<x INT>")))
        val sql = "WITH c AS (SELECT s FROM t), d AS (SELECT s FROM c) SELECT s.* FROM d"
        assertEquals(listOf("x"), SqlFragment(sql, "bigquery").outputShape(inputs).columns.map { it.name })
        for (modifier in listOf("ORDER BY 1", "GROUP BY 1", "GROUP BY 1 ORDER BY 1")) {
            assertEquals(listOf("INT"), SqlFragment("SELECT s.* FROM t $modifier", "bigquery").outputShape(inputs).columns.map { it.type })
        }
    }
    @Test fun unknownTvfPolicyAndSemiJoinVisibilityStayConservative() {
        val d = Dialects.DUCKDB
        assertFailsWith<OptimizeError> { qualify(d.parseOne("SELECT absent FROM unknown_fn() AS t"), dialect = d) }
        val shape = SqlFragment("SELECT absent FROM unknown_fn() AS t", "duckdb").outputShape()
        assertEquals(listOf("UNKNOWN"), shape.columns.map { it.type })
        val schema = mapOf("a" to mapOf("x" to "INT"), "b" to mapOf("y" to "INT"))
        val q = qualify(d.parseOne("SELECT x FROM a SEMI JOIN b ON x=y"), dialect = d, schema = schema)
        assertEquals("a", q.findAll<Column>().first { it.name == "x" }.table)
        assertFailsWith<OptimizeError> { qualify(d.parseOne("SELECT y FROM a SEMI JOIN b ON x=y"), dialect = d, schema = schema) }
    }
    @Test fun cteTypingDoesNotErasePhysicalLineageOrInventMissingColumns() {
        val inputs = ShapeCatalog(mapOf("t" to Shape.of("x" to "INT")))
        val fragment = SqlFragment("WITH c AS (SELECT x FROM t UNION ALL SELECT CAST(x AS DOUBLE) FROM t) SELECT x FROM c", "duckdb")
        assertEquals("DOUBLE", fragment.outputShape(inputs).columns.single().type)
        assertEquals(setOf("t"), fragment.columnDependencies(inputs = inputs).getValue("x"))
        val qualified = fragment.toStandardSql(inputs = inputs, expandStars = true)
        assertContains(qualified, "UNION ALL")
        assertFailsWith<OptimizeError> { qualify(Dialects.DUCKDB.parseOne("SELECT absent FROM t"),
            dialect = Dialects.DUCKDB, schema = mapOf("t" to mapOf("x" to "INT")), inferSchema = false) }
    }
}
