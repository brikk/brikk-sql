package dev.brikk.house.sql.scope

import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Valid SQL as deep as editors meet never exhausts the stack: it lowers, and it scopes or yields UNKNOWN. */
class RelationScopeDepthTest {
    private val facts = RelationFacts(slots = mapOf("src" to RelationFact(listOf(ColumnFact("c1"), ColumnFact("c2")))))

    private fun conditions(n: Int) = (1..n).joinToString(" AND ") { "c1 > $it" }

    @Test
    fun longConditionChainsLowerAndScope() {
        val sql = "FROM src() |> WHERE ${conditions(5_000)} |> SELECT c1"
        desugarPipes(Dialects.DUCKDB.parseOne(sql), Dialects.DUCKDB)
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertEquals(5_001, scope.references.count { it.kind == ReferenceKind.COLUMN && it.resolution == Resolution.RESOLVED })
        assertTrue(RelationScope.analyze("SELECT c1 FROM src() WHERE ${conditions(5_000)} AND nope", "duckdb", facts)
            .diagnostics.single().problem == ScopeProblem.MISSING_COLUMN)
    }

    @Test
    fun longSetOperationChainsScope() {
        val sql = (1..2_000).joinToString(" UNION ALL ") { "SELECT c1, c2 FROM src()" } + " ORDER BY c2"
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertEquals(listOf("c1", "c2"), scope.output?.columns?.map { it.name })
        assertTrue(scope.diagnostics.isEmpty())
    }

    @Test
    fun deepNestingScopesAndNestingThatDoesNotParseIsNoCrash() {
        fun nested(n: Int): String {
            var sql = "SELECT c1 FROM src()"
            repeat(n) { sql = "SELECT c1 FROM ($sql) x$it" }
            return sql
        }
        val deep = nested(60)
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), RelationScope.analyze("$deep WHERE nope > 0", "duckdb", facts).diagnostics.map { it.problem })
        assertEquals(listOf("c1"), RelationScope.complete(deep, "duckdb", facts, "SELECT ".length).items.map { it.name })
        assertFalse(RelationScope.analyze(nested(200), "duckdb", facts).parsed)
        RelationScope.complete(nested(200), "duckdb", facts, "SELECT ".length)
    }
}
