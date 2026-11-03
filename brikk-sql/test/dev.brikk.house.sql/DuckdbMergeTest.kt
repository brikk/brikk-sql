package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Insert
import dev.brikk.house.sql.ast.Merge
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.SqlglotError
import dev.brikk.house.sql.optimizer.lineageAll
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.ReferenceKind
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.ScopeProblem
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckdbMergeTest {
    private val head = "MERGE INTO target AS t USING source AS s USING (id) "
    private val facts = RelationFacts(tables = mapOf(
        "target" to RelationFact(listOf(ColumnFact("id", "INT"), ColumnFact("v", "INT"), ColumnFact("target_only", "INT"))),
        "source" to RelationFact(listOf(ColumnFact("id", "INT"), ColumnFact("v", "INT"), ColumnFact("source_only", "INT"))),
    ), catalog = CatalogKnowledge.CLOSED)

    @Test
    fun documentedMergeFormsKeepStructuredActionsConditionsAndSerde() {
        for (sql in listOf(
            "MERGE INTO target USING source ON target.id = source.id WHEN MATCHED THEN UPDATE WHEN NOT MATCHED THEN INSERT",
            head + "WHEN MATCHED THEN UPDATE SET v = s.v",
            head + "WHEN MATCHED THEN DELETE",
            head + "WHEN MATCHED AND t.v < 100 THEN UPDATE SET v = s.v WHEN MATCHED AND t.v > 100 THEN DELETE WHEN NOT MATCHED THEN INSERT BY NAME RETURNING merge_action, *",
            head + "WHEN NOT MATCHED BY SOURCE THEN DELETE RETURNING merge_action, *",
            head + "WHEN NOT MATCHED BY TARGET THEN INSERT (id, v) VALUES (s.id, s.v)",
        )) {
            val tree = assertIs<Merge>(Dialects.DUCKDB.parseOne(sql))
            assertEquals(tree, Serde.load(Serde.dump(tree)), sql)
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"), sql)
        }
    }

    @Test
    fun insertByNameIsAnActionPropertyNotAPositionalInsertOrAnOpaqueName() {
        val sql = head + "WHEN NOT MATCHED THEN INSERT BY NAME"
        val tree = Dialects.DUCKDB.parseOne(sql)
        assertEquals(true, tree.findAll<Insert>().single().args["by_name"])
        assertTrue(tree.sql("duckdb").contains("INSERT BY NAME"))
        assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("postgres") }
        assertTrue(SqlFragment(sql, "duckdb").transpileTo("postgres").unsupportedMessages.any { "INSERT BY NAME" in it })
    }

    @Test
    fun mergeBranchesHaveTheirOwnAvailableRelationsAndReturningActionType() {
        val sql = head + "WHEN MATCHED THEN UPDATE SET v = s.v WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v) RETURNING merge_action, t.v"
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        val action = scope.references.single { it.kind == ReferenceKind.COLUMN && it.name == "merge_action" }.columns.single()
        assertEquals("VARCHAR", action.sqlType)
        assertNull(scope.output, "Symbol typing is not an assignment/result-lineage contract")
        val target = facts.tables.getValue("target")
        val collision = facts.copy(tables = facts.tables + ("target" to target.copy(columns = target.columns + ColumnFact("merge_action", "INT", provenance = "physical:action"))))
        val collided = RelationScope.analyze(sql, "duckdb", collision).references.single { it.kind == ReferenceKind.COLUMN && it.name == "merge_action" }.columns.single()
        assertEquals("INT", collided.sqlType)
        assertEquals("physical:action", collided.provenance)
        val completing = head + "WHEN MATCHED THEN UPDATE SET v = s.v RETURNING "
        val offered = RelationScope.complete(completing, "duckdb", collision, completing.length).items.filter { it.name == "merge_action" }
        assertEquals(1, offered.size)
        assertEquals("INT", offered.single().column?.sqlType)
        val partial = facts.copy(tables = facts.tables + ("target" to target.copy(knowledge = Knowledge.PARTIAL)))
        val uncertain = RelationScope.analyze(sql, "duckdb", partial)
        assertTrue(uncertain.diagnostics.isEmpty(), uncertain.diagnostics.toString())
        val unknownAction = uncertain.references.single { it.kind == ReferenceKind.COLUMN && it.name == "merge_action" }.columns.single()
        assertNull(unknownAction.sqlType)
        assertEquals(ColumnOrigin.RELATION_CONTRACT, unknownAction.origin)
        for ((suffix, name) in listOf(
            "WHEN NOT MATCHED BY SOURCE AND s.v > 0 THEN DELETE" to "s",
            "WHEN NOT MATCHED BY TARGET AND t.v > 0 THEN INSERT (id, v) VALUES (s.id, s.v)" to "t",
            "WHEN MATCHED THEN UPDATE SET v = s.v RETURNING s.v" to "s",
        )) {
            val invalid = RelationScope.analyze(head + suffix, "duckdb", facts)
            assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), invalid.diagnostics.map { it.problem }, "$name: $suffix")
        }
    }

    @Test
    fun assignmentAndInsertCompletionDoNotConfuseSourceValuesWithTargetColumns() {
        fun offered(tail: String): List<String> {
            val sql = head + tail
            val caret = sql.indexOf("<caret>")
            return RelationScope.complete(sql.replace("<caret>", ""), "duckdb", facts, caret).items.map { it.name }
        }
        assertEquals(listOf("id", "v", "target_only"), offered("WHEN MATCHED THEN UPDATE SET <caret> = s.v"))
        assertEquals(listOf("id", "v", "source_only"), offered("WHEN MATCHED THEN UPDATE SET v = s.<caret>"))
        assertEquals(listOf("id", "v", "source_only"), offered("WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.<caret>)"))
        assertEquals(listOf("id", "v", "target_only"), offered("WHEN NOT MATCHED THEN INSERT (id, <caret>) VALUES (s.id, s.v)"))
    }

    @Test
    fun queryLineageRefusesTheWholeMergeRatherThanReturningItsUsingQuery() {
        assertFailsWith<SqlglotError> {
            lineageAll("MERGE INTO target t USING (SELECT id, v FROM source) s ON t.id = s.id WHEN MATCHED THEN UPDATE SET v = s.v", dialect = Dialects.DUCKDB)
        }
    }
}
