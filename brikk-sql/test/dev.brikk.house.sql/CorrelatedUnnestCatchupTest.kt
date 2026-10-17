package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.optimizer.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CorrelatedUnnestCatchupTest {
    private fun typed(source: String, schema: Any? = null, policy: Boolean? = false): Expression {
        val tree = qualify(Dialects.BIGQUERY.parseOne(source), dialect = Dialects.BIGQUERY,
            schema = schema, inferSchema = policy)
        return annotateTypes(tree, schema = schema, dialect = Dialects.BIGQUERY)
    }
    private fun types(tree: Expression) = tree.selects.map { it.type?.sql("bigquery") }

    @Test
    fun correlatedUnnestUsesComputedAndNestedProjectionTypesWithoutSchema() {
        for (policy in listOf(true, false, null)) {
            val tree = typed("""
                WITH t AS (SELECT [STRUCT('a' AS email, CAST(2 AS FLOAT64) AS magnitude)] AS items)
                SELECT (SELECT email FROM UNNEST(items)) AS linked_email,
                    (SELECT MAX(magnitude) FROM UNNEST(items)) AS gallons FROM t
            """.trimIndent(), policy = policy)
            assertEquals(listOf("STRING", "FLOAT64"), types(tree), tree.sql("bigquery"))
            val nested = typed("""
                WITH t AS (SELECT [STRUCT([STRUCT(CAST(2 AS FLOAT64) AS magnitude)] AS segments)] AS legs)
                SELECT (SELECT MAX(segments[SAFE_OFFSET(0)].magnitude) FROM UNNEST(legs)) AS gallons FROM t
            """.trimIndent(), policy = policy)
            assertEquals(listOf("FLOAT64"), types(nested), nested.sql("bigquery"))
        }
    }

    @Test
    fun computedProjectionShadowsSameNamedPhysicalColumnAndAliasListsRemainVisible() {
        val schema = mapOf("tbl" to mapOf("a" to "INT64"))
        val source = "WITH t AS (SELECT [STRUCT(1 AS b)] AS a FROM tbl) SELECT (SELECT b FROM UNNEST(a)) AS x FROM t"
        for (policy in listOf(true, false, null)) {
            assertEquals(listOf("INT64"), types(typed(source, schema, policy)))
        }
        val aliases = typed("WITH t(items) AS (SELECT [STRUCT('a' AS email)] AS hidden) " +
            "SELECT (SELECT email FROM UNNEST(items)) AS linked_email FROM t")
        assertEquals(listOf("STRING"), types(aliases))
        val query = Dialects.BIGQUERY.parseOne(source)
        val noParameter = qualify(query, dialect = Dialects.BIGQUERY, schema = schema)
        assertEquals(listOf("INT64"), types(annotateTypes(noParameter, schema = schema, dialect = Dialects.BIGQUERY)))
    }

    @Test
    fun missingStructFieldsAreRejectedRatherThanBorrowedFromOuterTables() {
        val source = "WITH t AS (SELECT [STRUCT('a' AS email)] AS items) " +
            "SELECT (SELECT missing FROM UNNEST(items)) AS x FROM t"
        assertFailsWith<OptimizeError> { typed(source) }
        val local = typed("WITH t AS (SELECT [STRUCT('outer' AS email)] AS items) " +
            "SELECT (SELECT email FROM UNNEST([STRUCT(CAST(1 AS INT64) AS email)])) AS x FROM t")
        assertEquals(listOf("INT64"), types(local))
        val nested = typed("WITH t AS (SELECT [STRUCT('outer' AS email)] AS items) " +
            "SELECT (SELECT (SELECT email FROM UNNEST(items)) FROM " +
            "(SELECT [STRUCT(CAST(1 AS INT64) AS email)] AS items) AS t) AS x FROM t")
        assertEquals(listOf("INT64"), types(nested))
        assertFailsWith<OptimizeError> {
            typed("WITH t AS (SELECT [STRUCT('outer' AS email)] AS items) " +
                "SELECT (SELECT (SELECT email FROM UNNEST(items)) FROM " +
                "(SELECT MYSTERY([STRUCT(1 AS email)]) AS items) AS t) AS x FROM t")
        }
    }

    @Test
    fun correlatedInputsCrossOnlyPermittedOuterScopesAndStarsUseProvenFields() {
        val prefix = "WITH t AS (SELECT [STRUCT('a' AS email, CAST(2 AS FLOAT64) AS magnitude)] AS items) "
        for (policy in listOf(true, false, null)) {
            val nested = typed(prefix + "SELECT (SELECT (SELECT email FROM UNNEST(items))) AS x FROM t",
                policy = policy)
            assertEquals(listOf("STRING"), types(nested))
            for (star in listOf("*", "e.*")) {
                val alias = if (star == "*") "" else " AS e"
                val tree = typed(prefix + "SELECT (SELECT AS STRUCT $star FROM UNNEST(items)$alias) AS x FROM t",
                    policy = policy)
                val fields = tree.findAll<Subquery>().first().unnest().selects
                assertEquals(listOf("email", "magnitude"), fields.map { it.aliasOrName })
                assertEquals(listOf("STRING", "FLOAT64"), fields.map { it.type?.sql("bigquery") })
            }
        }
        assertFailsWith<OptimizeError> {
            typed(prefix + "SELECT (SELECT email FROM UNNEST(items)) AS x FROM (SELECT 1 AS n) AS d")
        }
    }

    @Test
    fun unknownComputationsAndSetOperationsNeverBorrowUnderlyingStructFields() {
        val schema = mapOf("tbl" to mapOf("items" to "ARRAY<STRUCT<email STRING>>", "other" to "INT64"))
        for (projection in listOf("MYSTERY(items)", "other")) {
            val source = "WITH t AS (SELECT $projection AS items FROM tbl) SELECT (SELECT email FROM UNNEST(items)) AS x FROM t"
            assertFailsWith<OptimizeError> { typed(source, schema) }
        }
        val unknown = "WITH t AS (SELECT MYSTERY(items) AS items FROM tbl) SELECT (SELECT email FROM UNNEST(items)) AS x FROM t"
        for (policy in listOf(true, false, null)) {
            val tree = qualify(Dialects.BIGQUERY.parseOne(unknown), dialect = Dialects.BIGQUERY,
                schema = schema, inferSchema = policy, validateQualifyColumns = false)
            annotateTypes(tree, schema = schema, dialect = Dialects.BIGQUERY)
            assertEquals(listOf("UNKNOWN"), types(tree))
            assertTrue(tree.findAll<Unnest>().single().isType(DType.UNKNOWN))
        }
        val setop = "WITH t AS (SELECT items FROM tbl UNION ALL SELECT items FROM tbl) " +
            "SELECT (SELECT email FROM UNNEST(items)) AS x FROM t"
        // The frozen target explicitly reverted UNNEST discovery through set-op
        // projections. Do not guess from the first arm's physical schema.
        assertFailsWith<OptimizeError> { typed(setop, schema) }
        assertEquals(listOf("STRING"), types(typed("WITH t AS (SELECT items AS renamed FROM tbl) " +
            "SELECT (SELECT email FROM UNNEST(renamed)) AS x FROM t", schema)))
    }

    @Test
    fun explicitTvfColumnsAndPivotedCteOutputsUseEvidenceNotDynamicInference() {
        val source = "SELECT t.x FROM READ_PARQUET('path.parquet', hive_partitioning=true) AS t"
        val tree = qualify(Dialects.DUCKDB.parseOne(source), dialect = Dialects.DUCKDB)
        annotateTypes(tree, dialect = Dialects.DUCKDB)
        assertEquals(listOf("UNKNOWN"), tree.selects.map { it.type?.sql("duckdb") })
        assertEquals("t", tree.findAll<Column>().first { it.name == "x" }.table)
        assertFailsWith<OptimizeError> {
            qualify(Dialects.DUCKDB.parseOne("SELECT x FROM READ_PARQUET('path.parquet') AS t"), dialect = Dialects.DUCKDB)
        }
        for (policy in listOf(true, null)) {
            val legacy = qualify(Dialects.DUCKDB.parseOne("SELECT x FROM READ_PARQUET('path.parquet') AS t"),
                dialect = Dialects.DUCKDB, inferSchema = policy)
            assertEquals("t", legacy.findAll<Column>().first { it.name == "x" }.table)
            annotateTypes(legacy, dialect = Dialects.DUCKDB)
            assertEquals(listOf("UNKNOWN"), legacy.selects.map { it.type?.sql("duckdb") })
        }
        for (policy in listOf(true, false, null)) {
            val pivot = qualify(Dialects.DUCKDB.parseOne("WITH sales AS (SELECT 1 AS id, 'q1' AS quarter, 2 AS amount) " +
                "SELECT id, q1 FROM sales PIVOT(SUM(amount) FOR quarter IN ('q1' AS q1)) AS p"),
                dialect = Dialects.DUCKDB, inferSchema = policy)
            annotateTypes(pivot, dialect = Dialects.DUCKDB)
            assertEquals(listOf("id", "q1"), pivot.selects.map { it.aliasOrName })
            assertEquals(listOf("INT", "BIGINT"), pivot.selects.map { it.type?.sql("duckdb") })
            assertEquals(setOf("p"), pivot.selects.flatMap { it.findAll<Column>().map { c -> c.table }.toList() }.toSet())
        }
        val lateral = qualify(Dialects.SPARK.parseOne("SELECT name, ci.age FROM my_table LATERAL VIEW EXPLODE(items) ci AS ci"),
            dialect = Dialects.SPARK, schema = mapOf("my_table" to mapOf("items" to "ARRAY<STRUCT<name STRING, age INT>>")))
        annotateTypes(lateral, schema = mapOf("my_table" to mapOf("items" to "ARRAY<STRUCT<name STRING, age INT>>")), dialect = Dialects.SPARK)
        assertEquals(listOf("STRING", "INT"), lateral.selects.map { it.type?.sql("spark") })
        // Excluding inherited declarations must not hide real non-projected
        // SEMI/ANTI inputs from their own join predicates' type annotation.
        val joinSchema = mapOf("a" to mapOf("id" to "INT"), "b" to mapOf("id" to "BIGINT"))
        for (join in listOf("SEMI", "ANTI")) {
            val predicate = qualify(Dialects.DUCKDB.parseOne("SELECT a.id FROM a $join JOIN b ON a.id = b.id"),
                dialect = Dialects.DUCKDB, schema = joinSchema)
            annotateTypes(predicate, schema = joinSchema, dialect = Dialects.DUCKDB)
            assertEquals("BIGINT", predicate.findAll<Column>().single { it.table == "b" }.type?.sql("duckdb"))
        }
    }

    @Test
    fun passThroughTracingMemoizesKnownAndUnknownTypesAcrossRepeatedColumnEnumeration() {
        for (known in listOf(true, false)) {
            var lookups = 0
            val base = MappingSchema(mapOf("tbl" to mapOf("items" to
                if (known) "ARRAY<STRUCT<email STRING>>" else "UNKNOWN")), dialect = Dialects.BIGQUERY)
            val counted = object : Schema by base {
                override fun getColumnType(table: Any, column: Any, dialect: dev.brikk.house.sql.dialects.Dialect?,
                    normalize: Boolean?): DataType {
                    lookups++
                    return base.getColumnType(table, column, dialect, normalize)
                }
            }
            val ctes = mutableListOf("q0 AS (SELECT b.items AS items FROM tbl AS b)")
            for (level in 1..12) ctes += "q$level AS (SELECT l.items AS items FROM q${level - 1} AS l CROSS JOIN q${level - 1} AS r)"
            val source = "WITH ${ctes.joinToString(", ")} SELECT (SELECT email FROM UNNEST(items)) FROM q12"
            val scopes = traverseScope(qualifyTables(Dialects.BIGQUERY.parseOne(source), dialect = Dialects.BIGQUERY))
            val child = scopes.first { it.isSubquery && it.expression is Select }
            val resolver = Resolver(child, counted)
            val alias = child.selectedSources.keys.single()
            val columns = resolver.getSourceColumns(alias)
            assertEquals(known, "email" in columns)
            assertEquals(columns, resolver.getSourceColumns(alias, onlyVisible = true))
            assertEquals(columns, resolver.getSourceColumns(alias))
            assertEquals(1, lookups, "Both known and UNKNOWN results must be memoized")
        }
    }

    @Test
    fun cachedOuterResolversRespectCorrelationBoundariesAndInferencePolicies() {
        for (policy in listOf(true, false)) {
            val tree = Dialects.DUCKDB.parseOne("SELECT (SELECT (SELECT mystery)) FROM unknown_table")
            val root = assertNotNull(buildScope(tree))
            val inner = root.subqueryScopes.single().subqueryScopes.single()
            val resolver = Resolver(inner, MappingSchema(dialect = Dialects.DUCKDB), policy)
            val outer = resolver.outerResolvers()
            assertSame(outer, resolver.outerResolvers())
            assertEquals(listOf(inner.parent, root), outer.map { it.scope })
            assertEquals(if (policy) "unknown_table" else null, outer.last().getTable("mystery")?.name)
            assertTrue(Resolver(root, resolver.schema, policy).outerResolvers().isEmpty())
        }
        val derived = assertNotNull(buildScope(Dialects.DUCKDB.parseOne("SELECT * FROM (SELECT x FROM t) AS d")))
            .derivedTableScopes.single()
        assertFalse(derived.canBeCorrelated)
        assertTrue(Resolver(derived, MappingSchema(dialect = Dialects.DUCKDB)).outerResolvers().isEmpty())
    }
}
