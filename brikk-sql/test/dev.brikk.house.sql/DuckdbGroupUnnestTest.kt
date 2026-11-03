package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Explode
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.ScopeProblem
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckdbGroupUnnestTest {
    private val facts = RelationFacts(tables = mapOf("items" to RelationFact(listOf(
        ColumnFact("tags", "VARCHAR[]", nullability = Nullability.NON_NULL, provenance = "items.tags"),
        ColumnFact("weight", "INT", nullability = Nullability.NON_NULL),
    ))), catalog = CatalogKnowledge.CLOSED)
    private val catalog = ShapeCatalog(mapOf("items" to Shape(listOf(
        ColumnShape("tags", "VARCHAR[]", false), ColumnShape("weight", "INT", false),
    ))))
    private val schema = mapOf("items" to mapOf("tags" to "VARCHAR[]", "weight" to "INT"))
    private val query = "SELECT unnest(tags) AS tag,count(*) AS n FROM items GROUP BY unnest(tags) ORDER BY tag"

    @Test
    fun scalarGroupingExpressionsSurviveCopySerdeAndQualificationWithoutNewTableSources() {
        for (group in listOf("unnest(tags)", "tag", "1")) {
            val sql = query.replace("GROUP BY unnest(tags)", "GROUP BY $group")
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree, tree.copy())
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
            val qualified = qualify(tree.copy(), dialect = Dialects.DUCKDB, schema = schema)
            assertTrue(qualified.findAll<Explode>().any())
            assertTrue(qualified.findAll<Unnest>().none())
            assertEquals(listOf("items"), qualified.findAll<Table>().map { it.name }.toList())
        }
        for (sql in listOf(
            "SELECT upper(trim(unnest(tags))) AS tag,count(*) AS n FROM items GROUP BY upper(trim(unnest(tags)))",
            "SELECT unnest(tags) AS tag,unnest(tags) AS again,count(*) AS n FROM items GROUP BY tag",
            "SELECT unnest([[1,2],[2,3]],recursive:=true) AS v,count(*) AS n GROUP BY v",
        )) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
        }
    }

    @Test
    fun aliasesAndNestedExpressionsHaveElementTypesButNotListNullabilityOrDirectProvenance() {
        for (group in listOf("unnest(tags)", "tag", "1")) {
            val sql = query.replace("GROUP BY unnest(tags)", "GROUP BY $group")
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
            val tag = scope.output!!.columns.first()
            assertEquals("TEXT", tag.sqlType)
            assertEquals(ColumnOrigin.COMPUTED, tag.origin)
            assertNull(tag.provenance)
            assertEquals(Nullability.UNKNOWN, tag.nullability)
            assertEquals(listOf(ColumnShape("tag", "TEXT", null), ColumnShape("n", "BIGINT", false)),
                SqlFragment(sql, "duckdb").outputShape(catalog).columns)
        }
        val wrapped = "SELECT upper(trim(unnest(tags))) AS tag,count(*) AS n,max(weight) AS largest FROM items GROUP BY tag HAVING count(*)>1"
        val scope = RelationScope.analyze(wrapped, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        assertEquals("VARCHAR", scope.output!!.columns.first().sqlType)
        assertEquals(listOf("VARCHAR", "BIGINT", "INT"), SqlFragment(wrapped, "duckdb").outputShape(catalog).columns.map { it.type })
        val padded = SqlFragment("SELECT unnest([1,2]) AS a,unnest([10]) AS b,count(*) AS n GROUP BY a,b", "duckdb").outputShape()
        assertEquals(listOf("INT", "INT", "BIGINT"), padded.columns.map { it.type })
        assertNull(padded.columns[1].nullable, "A non-null list can still yield padded NULL elements")
    }

    @Test
    fun groupingCompletionSeesInputsAndProjectionAliasesAndUnknownFieldsStillDiagnose() {
        fun names(sql: String): List<String> {
            val caret = sql.indexOf("<caret>")
            return RelationScope.complete(sql.replace("<caret>", ""), "duckdb", facts, caret).items.map { it.name }
        }
        assertTrue(names("SELECT unnest(tags) AS tag,count(*) AS n FROM items GROUP BY <caret>").containsAll(listOf("tags", "weight", "tag")))
        assertTrue(names("SELECT unnest(tags) AS tag,count(*) AS n FROM items GROUP BY unnest(<caret>)").contains("tags"))
        val invalid = RelationScope.analyze(query.replace("unnest(tags)", "unnest(missing)"), "duckdb", facts)
        assertTrue(invalid.diagnostics.any { it.problem == ScopeProblem.MISSING_COLUMN })
    }

    @Test
    fun nestedListGroupingTypesRespectRecursiveAndDepthOptions() {
        for ((option, type) in listOf("" to "ARRAY<INT>", ",recursive:=false" to "ARRAY<INT>",
            ",recursive:=true" to "INT", ",max_depth:=1" to "ARRAY<INT>", ",max_depth:=2" to "INT")) {
            val sql = "SELECT unnest([[1,2],[2,3]]$option) AS v,count(*) AS n FROM items GROUP BY v"
            assertEquals(type, SqlFragment(sql, "duckdb").outputShape(catalog).columns.first().type, option)
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), "$option: ${scope.diagnostics}")
            assertEquals(type, scope.output!!.columns.first().sqlType, option)
        }
        val unknown = "SELECT unnest([[1,2]],recursive:=\$flag) AS v,count(*) AS n FROM items GROUP BY v"
        assertEquals("UNKNOWN", SqlFragment(unknown, "duckdb").outputShape(catalog).columns.first().type)
        assertNull(RelationScope.analyze(unknown, "duckdb", facts).output!!.columns.first().sqlType)
    }

    @Test
    fun crossTargetGenerationRefusesUnprovedGroupingRewritesIncludingAliasAndOrdinalForms() {
        for (sql in listOf(query, query.replace("GROUP BY unnest(tags)", "GROUP BY tag"), query.replace("GROUP BY unnest(tags)", "GROUP BY 1"),
            "WITH g AS ($query) SELECT * FROM g",
            "SELECT count(*) AS n FROM items GROUP BY unnest(tags)")) {
            for (target in listOf("trino", "postgres", "doris", "clickhouse")) {
                val fragment = SqlFragment(sql, "duckdb")
                assertFailsWith<UnsupportedError>("$target: $sql") { fragment.toExecutable(target) }
                assertTrue(fragment.transpileTo(target).unsupportedMessages.any { "UNNEST" in it && "group" in it.lowercase() }, target)
            }
        }
        assertTrue(SqlFragment("SELECT unnest([1,2]) AS v", "duckdb").transpileTo("trino").unsupportedMessages.isEmpty())
        assertTrue(SqlFragment("SELECT v,count(*) AS n FROM unnest([1,2]) AS t(v) GROUP BY v", "duckdb").transpileTo("trino").unsupportedMessages.isEmpty())
        assertTrue(SqlFragment("SELECT weight,count(*) AS n FROM items GROUP BY weight", "duckdb").transpileTo("trino").unsupportedMessages.isEmpty())
    }
}
