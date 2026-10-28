package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Update
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.SqlglotError
import dev.brikk.house.sql.optimizer.lineageAll
import dev.brikk.house.sql.optimizer.qualifyTables
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ReferenceKind
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.Resolution
import dev.brikk.house.sql.scope.ScopeProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckdbUpdateTest {
    private val self = "UPDATE original AS true_original SET value = " +
        "(SELECT new.value || ' a change!' AS value FROM original AS new WHERE true_original.key = new.key)"
    private fun relation(vararg columns: String) = RelationFact(columns.map { ColumnFact(it, "VARCHAR", provenance = it) })
    private val facts = RelationFacts(tables = mapOf(
        "original" to relation("key", "value", "target_only"),
        "replacement" to relation("key", "value", "source_only"),
    ), catalog = CatalogKnowledge.CLOSED)

    @Test
    fun updateFormsKeepTheirTargetsSourcesAliasesAndSerde() {
        for (sql in listOf(
            self,
            "UPDATE city SET revenue = revenue + 100 FROM country WHERE city.country_code = country.code AND country.name = 'France'",
            "UPDATE city AS t SET revenue = t.revenue + r.bonus FROM country AS c JOIN region AS r ON c.code = r.code WHERE t.country_code = c.code RETURNING *",
            "UPDATE original AS t SET value = s.value FROM original AS s WHERE t.key = s.key",
            "UPDATE original AS t SET value = s.value FROM (SELECT key, value FROM replacement) AS s WHERE t.key = s.key",
        )) {
            val tree = assertIs<Update>(Dialects.DUCKDB.parseOne(sql))
            assertEquals(tree, Serde.load(Serde.dump(tree)), sql)
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"), sql)
            assertIs<Table>(tree.thisArg)
        }
        val tree = Dialects.DUCKDB.parseOne(self)
        val qualified = qualifyTables(tree.copy(), db = "main", catalog = "memory", dialect = Dialects.DUCKDB)
        assertEquals(listOf("true_original", "new"), qualified.findAll<Table>().map { it.alias }.toList())
        assertEquals(listOf("true_original", "new"), qualified.findAll<Column>().filter { it.name == "key" }.map { it.table }.toList())
        assertEquals(Dialects.DUCKDB.parseOne(self), tree, "Qualification must not mutate the caller's original tree")
    }

    @Test
    fun scalarSelfUpdateSeesTheOuterTargetWithoutCapturingTheInnerSource() {
        val scope = RelationScope.analyze(self, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        fun columnAt(text: String) = scope.references.single { it.kind == ReferenceKind.COLUMN && it.start == self.indexOf(text) + text.indexOf('.') + 1 }
        assertEquals("true_original", columnAt("true_original.key").columns.single().relation)
        assertEquals("new", columnAt("new.key").columns.single().relation)
        assertEquals("new", columnAt("new.value").columns.single().relation)
        assertNull(scope.output, "An UPDATE is not implicitly the result of its scalar SELECT")
    }

    @Test
    fun setTargetsUseOnlyTheTargetWhileValuesAndPredicatesSeeTheFromSources() {
        val sql = "UPDATE original AS t SET value = s.value FROM replacement AS s WHERE t.key = s.key"
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        val assignments = scope.references.filter { it.kind == ReferenceKind.COLUMN && it.name == "value" }
        assertEquals(listOf("t", "s"), assignments.map { it.columns.single().relation })
        val ambiguous = RelationScope.analyze("UPDATE original t SET value = value FROM replacement s WHERE t.key = s.key", "duckdb", facts)
        assertEquals(listOf(ScopeProblem.AMBIGUOUS_COLUMN), ambiguous.diagnostics.map { it.problem })
    }

    @Test
    fun updateCompletionUsesTheAssignmentSideAndCorrelatedFrame() {
        fun offered(sql: String): List<String> {
            val caret = sql.indexOf("<caret>")
            return RelationScope.complete(sql.replace("<caret>", ""), "duckdb", facts, caret).items.map { it.name }
        }
        assertEquals(listOf("key", "value", "target_only"), offered("UPDATE original t SET <caret> = s.value FROM replacement s"))
        assertEquals(listOf("key", "value", "source_only"), offered("UPDATE original t SET value = s.<caret> FROM replacement s"))
        assertEquals(listOf("key", "value", "target_only"), offered("UPDATE original t SET value = (SELECT t.<caret>)"))
    }

    @Test
    fun returningDoesNotImportFromSourcesAndFromJoinsCannotSeeTheTarget() {
        val returning = RelationScope.analyze("UPDATE original t SET value = s.value FROM replacement s WHERE t.key = s.key RETURNING s.source_only", "duckdb", facts)
        assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), returning.diagnostics.map { it.problem })
        val joined = RelationScope.analyze("UPDATE original t SET value = a.value FROM replacement a JOIN replacement b ON t.key = b.key", "duckdb", facts)
        assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), joined.diagnostics.map { it.problem })
        for (from in listOf("replacement a", "(SELECT key, value FROM replacement) a")) {
            val valid = RelationScope.analyze("UPDATE original t SET value = a.value FROM $from JOIN replacement b ON a.key = b.key RETURNING t.value", "duckdb", facts)
            assertTrue(valid.diagnostics.isEmpty(), valid.diagnostics.toString())
        }
    }

    @Test
    fun ctesAndInnerAliasesShadowOnlyTheirOwnScope() {
        val sql = "WITH s AS (SELECT key, value FROM replacement) UPDATE original t SET value = s.value FROM s WHERE t.key = s.key"
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        val shadowed = "UPDATE original t SET value = (SELECT t.value FROM replacement t WHERE t.key = '1')"
        val result = RelationScope.analyze(shadowed, "duckdb", facts)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val read = result.references.single { it.kind == ReferenceKind.COLUMN && it.start == shadowed.indexOf("t.value") + 2 }
        assertEquals("replacement", read.columns.single().source!!.relation)
        assertEquals(Resolution.RESOLVED, read.resolution)
    }

    @Test
    fun queryLineageDoesNotTreatAnUpdateSubqueryAsTheStatementResult() {
        for (sql in listOf(self, "UPDATE original SET value = s.value FROM replacement s WHERE original.key = s.key")) {
            val error = assertFailsWith<SqlglotError> { lineageAll(sql, dialect = Dialects.DUCKDB) }
            assertTrue(error.message.orEmpty().contains("query") || error.message.orEmpty().contains("SELECT"), error.message)
        }
    }
}
