package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.ReferenceKind
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.Resolution
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuckdbNestedSchemaTest {
    private fun fact(name: String) = RelationFact(listOf(ColumnFact(name, "INTEGER", nullability = Nullability.NON_NULL, provenance = name)))
    private val facts = RelationFacts(tables = mapOf(
        "memory.finance.reports.monthly.q3" to fact("revenue"),
        "memory.ops.reports.monthly.q3" to fact("expenses"),
        "memory.\"finance.reports\".monthly.q3" to fact("literal_dot"),
    ), catalog = CatalogKnowledge.CLOSED)

    @Test
    fun nestedDdlAndDmlKeepEveryComponentAndSerde() {
        for (sql in listOf(
            "CREATE SCHEMA memory.finance.reports.monthly",
            "CREATE TABLE memory.finance.reports.monthly.q3 (revenue INT)",
            "INSERT INTO memory.finance.reports.monthly.q3 VALUES (7)",
            "UPDATE memory.finance.reports.monthly.q3 SET revenue = 8",
            "DELETE FROM memory.finance.reports.monthly.q3 WHERE revenue = 8",
            "DROP TABLE memory.finance.reports.monthly.q3",
            "DROP SCHEMA memory.finance.reports.monthly",
            "SELECT * FROM \"memory\".\"finance.reports\".\"monthly\".\"q3\"",
        )) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree, Serde.load(Serde.dump(tree)), sql)
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"), sql)
            assertTrue(generated.contains(if ('"' in sql) "\"finance.reports\".\"monthly\".\"q3\"" else "memory.finance.reports.monthly"), generated)
        }
        val table = Dialects.DUCKDB.parseOne("SELECT * FROM memory.finance.reports.monthly.q3").find(Table::class) as Table
        assertEquals(listOf("memory", "finance", "reports", "monthly", "q3"), table.parts.map { it.name })
    }

    @Test
    fun deepTablesResolveColumnsAndCarryPhysicalProvenance() {
        for (query in listOf(
            "SELECT revenue FROM memory.finance.reports.monthly.q3",
            "SELECT q.revenue FROM memory.finance.reports.monthly.q3 q",
            "SELECT memory.finance.reports.monthly.q3.revenue FROM memory.finance.reports.monthly.q3",
            "SELECT finance.reports.monthly.q3.revenue FROM finance.reports.monthly.q3",
        )) {
            val scope = RelationScope.analyze(query, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), "$query: ${scope.diagnostics}")
            val column = scope.output!!.columns.single()
            assertEquals("revenue", column.name, query)
            assertEquals("revenue", column.provenance, query)
            assertEquals(Nullability.NON_NULL, column.nullability, query)
            assertEquals("memory.finance.reports.monthly.q3", scope.references.single { it.kind == ReferenceKind.TABLE }.relation!!.source, query)
        }
    }

    @Test
    fun quotedDotsDoNotBecomeNestedSchemaSeparators() {
        val sql = "SELECT literal_dot FROM memory.\"finance.reports\".monthly.q3"
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        assertEquals("literal_dot", scope.output!!.columns.single().provenance)
        val nested = RelationScope.analyze("SELECT revenue FROM memory.finance.reports.monthly.q3", "duckdb", facts)
        assertEquals("revenue", nested.output!!.columns.single().provenance)
    }

    @Test
    fun deepCompletionUsesTheWholeQualifier() {
        val tables = "SELECT * FROM memory.finance.reports.monthly."
        assertEquals(listOf("q3"), RelationScope.complete(tables, "duckdb", facts, tables.length).items.map { it.name })
        for (query in listOf(
            "SELECT  FROM memory.finance.reports.monthly.q3",
            "SELECT memory.finance.reports.monthly.q3. FROM memory.finance.reports.monthly.q3",
        )) {
            val caret = query.indexOf(" FROM")
            assertEquals(listOf("revenue"), RelationScope.complete(query, "duckdb", facts, caret).items.map { it.name }, query)
        }
    }

    @Test
    fun wrongExplicitParentsDoNotResolveToSameNamedTables() {
        val scope = RelationScope.analyze("SELECT revenue FROM memory.wrong.reports.monthly.q3", "duckdb", facts)
        assertEquals(Resolution.UNRESOLVED, scope.references.single { it.kind == ReferenceKind.TABLE }.resolution)
        assertTrue(scope.diagnostics.isNotEmpty())
    }

    @Test
    fun optimizerSchemaFindsAllLevelsInsteadOfTruncatingToThree() {
        val mapping = MappingSchema(mapOf("memory" to mapOf("finance" to mapOf("reports" to mapOf("monthly" to mapOf(
            "q3" to mapOf("revenue" to "INTEGER"),
        ))))), dialect = Dialects.DUCKDB)
        assertEquals(5, mapping.depth())
        assertEquals(listOf("revenue"), mapping.columnNames("memory.finance.reports.monthly.q3"))
        assertTrue(mapping.columnNames("other.finance.reports.monthly.q3").isEmpty())
    }

    @Test
    fun shapeAndLineagePreserveNestedTableIdentity() {
        val catalog = ShapeCatalog(mapOf("memory.finance.reports.monthly.q3" to Shape(listOf(ColumnShape("revenue", "INT", false)))))
        val fragment = SqlFragment("SELECT q.revenue FROM memory.finance.reports.monthly.q3 q", "duckdb")
        assertEquals(listOf("revenue"), fragment.outputShape(catalog).names())
        assertEquals("INT", fragment.outputShape(catalog).columns.single().type)
        assertEquals(mapOf("revenue" to setOf("memory.finance.reports.monthly.q3")), fragment.columnDependencies(inputs = catalog))
        val qualified = SqlFragment("SELECT memory.finance.reports.monthly.q3.revenue FROM memory.finance.reports.monthly.q3", "duckdb")
        assertEquals(listOf("revenue"), qualified.outputShape(catalog).names())
        assertEquals("INT", qualified.outputShape(catalog).columns.single().type)
        assertEquals(mapOf("revenue" to setOf("memory.finance.reports.monthly.q3")), qualified.columnDependencies(inputs = catalog))
        val withSlot = catalog.copy(slots = mapOf("src" to Shape(listOf(ColumnShape("id", "INT")))))
        assertEquals(listOf("id"), SqlFragment("SELECT s.id FROM src() s", "duckdb").outputShape(withSlot).names())
    }

    @Test
    fun sourceAndLineageKeysRetainLiteralDots() {
        val key = "memory.\"finance.reports\".q3"
        val catalog = ShapeCatalog(mapOf(key to Shape(listOf(ColumnShape("revenue", "INT")))))
        val fragment = SqlFragment("SELECT q.revenue FROM $key q", "duckdb")
        assertEquals(listOf(key), fragment.sourceTables)
        assertEquals(mapOf("revenue" to setOf(key)), fragment.columnDependencies(inputs = catalog))
    }
}
