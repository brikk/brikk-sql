package dev.brikk.house.sql.scope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class RelationScopeTest {
    private val id = ColumnFact("id", displayType = "kotlin.Long", nullability = Nullability.NON_NULL, provenance = "decl:id")
    private val kind = ColumnFact("eventKind", displayType = "demo.Kind", nullability = Nullability.NULLABLE, provenance = "decl:kind")
    private val events = RelationFact(listOf(id, kind), provenance = "decl:events")
    private val facts = RelationFacts(
        slots = mapOf("events" to events, "partial" to events.copy(knowledge = Knowledge.PARTIAL), "unknown" to RelationFact(knowledge = Knowledge.UNKNOWN)),
        tables = mapOf("db.accounts" to RelationFact(listOf(
            ColumnFact("id", "INTEGER", nullability = Nullability.NON_NULL, origin = ColumnOrigin.PHYSICAL_TABLE),
            ColumnFact("Owner", "VARCHAR", origin = ColumnOrigin.PHYSICAL_TABLE),
        ))),
    )

    private fun analyze(sql: String, dialect: String = "duckdb", input: RelationFacts = facts) = RelationScope.analyze(sql, dialect, input)
    /** The column reference at the [nth] occurrence of the word [name] in [sql]. */
    private fun RelationScope.column(sql: String, name: String, nth: Int = 0): ScopeReference {
        val start = Regex("\\b$name\\b").findAll(sql).elementAt(nth).range.first
        return references.single { it.kind == ReferenceKind.COLUMN && it.start == start }
    }
    private fun RelationScope.problems() = diagnostics.map { it.problem }

    @Test
    fun resultsUseTheScriptsOffsetsForARegion() {
        val script = "SELECT 1;\n-- 😀\nSELECT e.id, missing FROM events() e;"
        val statement = script.indexOf("SELECT e")..script.lastIndex
        val scope = RelationScope.analyze(script, "duckdb", facts, statement)
        val reference = scope.references.single { it.name == "id" }
        assertEquals(script.indexOf("id,"), reference.start)
        assertEquals(id, reference.columns.single().source?.column)
        assertEquals("e", reference.columns.single().relation)
        val missing = scope.diagnostics.single()
        assertEquals(ScopeProblem.MISSING_COLUMN, missing.problem)
        assertEquals("missing", script.substring(missing.start, missing.end))
        assertSame(reference, scope.referenceAt(reference.start + 1))
    }

    @Test
    fun eachStatementOfARegionIsAnalyzedButOnlyOneQueryHasAnOutput() {
        val scope = analyze("SELECT id FROM events(); SELECT nope FROM events()")
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), scope.problems())
        assertNull(scope.output)
        assertEquals(listOf("id", "eventKind"), analyze("SELECT * FROM events()").output?.columns?.map { it.name })
    }

    @Test
    fun unparsableSqlHasNoScope() {
        val scope = analyze("SELECT FROM WHERE (")
        assertFalse(scope.parsed)
        assertTrue(scope.references.isEmpty())
    }

    @Test
    fun anOutputIsAFactAnotherRegionCanUse() {
        val first = analyze("SELECT id AS key, eventKind, 1 AS one FROM events()").output
        val second = analyze("SELECT p.key, p.one FROM prev() p", input = RelationFacts(slots = mapOf("prev" to checkNotNull(first))))
        val key = second.column("SELECT p.key, p.one FROM prev() p", "key").columns.single()
        assertEquals("decl:id", key.provenance)
        assertEquals(ColumnOrigin.DECLARED, key.origin)
        assertEquals(ColumnOrigin.COMPUTED, second.column("SELECT p.key, p.one FROM prev() p", "one").columns.single().origin)
    }

    @Test
    fun knowledgeDecidesWhatAnAbsentNameMeans() {
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), analyze("SELECT nope FROM events()").problems())
        assertEquals(listOf(ScopeProblem.PARTIAL_CONTRACT), analyze("SELECT nope FROM partial()").problems())
        assertEquals(ScopeDiagnostic.Severity.WARNING, analyze("SELECT nope FROM partial()").diagnostics.single().severity)
        assertTrue(analyze("SELECT nope FROM unknown()").diagnostics.isEmpty())
        assertTrue(analyze("SELECT nope FROM events() JOIN unknown() u ON true").diagnostics.isEmpty())
    }

    @Test
    fun tablesMatchByTheirLastPartsAndAClosedCatalogReportsTheRest() {
        val sql = "SELECT a.owner, a.id FROM accounts a JOIN db.accounts b ON a.id = b.id"
        val scope = analyze(sql)
        assertEquals("Owner", scope.column(sql, "owner").columns.single().name)
        assertTrue(scope.diagnostics.isEmpty())
        assertTrue(analyze("SELECT x FROM nowhere").diagnostics.isEmpty())
        val closed = analyze("SELECT x FROM nowhere", input = facts.copy(catalog = CatalogKnowledge.CLOSED))
        assertEquals(listOf(ScopeProblem.UNKNOWN_TABLE), closed.problems())
    }

    @Test
    fun slotsMatchExactlyAndOnlyWithoutArgumentsOrQualifiers() {
        assertTrue(analyze("SELECT e.nope FROM Events() e").diagnostics.isEmpty())
        assertTrue(analyze("SELECT e.nope FROM events(1) e").diagnostics.isEmpty())
        assertTrue(analyze("SELECT e.nope FROM db.events() e").diagnostics.isEmpty())
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), analyze("SELECT e.nope FROM events() e").problems())
    }

    @Test
    fun columnCaseFollowsTheDialect() {
        assertTrue(analyze("SELECT \"eventkind\" FROM events()", "duckdb").diagnostics.isEmpty())
        assertTrue(analyze("SELECT `EVENTKIND` FROM events()", "doris").diagnostics.isEmpty())
        assertTrue(analyze("SELECT EVENTKIND FROM events()", "postgres").diagnostics.isEmpty())
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), analyze("SELECT \"eventkind\" FROM events()", "postgres").problems())
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), analyze("SELECT EVENTKIND FROM events()", "clickhouse").problems())
        assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), analyze("SELECT E.id FROM events() e", "doris").problems())
    }

    @Test
    fun groupBySeesProjectionAliasesWhereTheDialectDoes() {
        val sql = "SELECT eventKind AS k, count(*) AS n FROM events() GROUP BY k"
        assertEquals(id.copy(name = "eventKind").name, analyze(sql).column(sql, "k", 1).columns.single().source?.column?.name)
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), analyze(sql, "trino").problems())
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), analyze("SELECT count(*) AS n FROM events() GROUP BY n").problems())
    }

    @Test
    fun namesThatAreNoColumnsAreNotReported() {
        assertTrue(analyze("SELECT list_transform([1], x -> x + id) FROM events()").diagnostics.isEmpty())
        val struct = RelationFacts(slots = mapOf("s" to RelationFact(listOf(ColumnFact("payload")))))
        assertTrue(analyze("SELECT payload.field FROM s()", input = struct).diagnostics.isEmpty())
    }

    @Test
    fun recursiveCtesSeeThemselvesAsUnknown() {
        val sql = "WITH RECURSIVE r AS (SELECT id FROM events() UNION ALL SELECT id + 1 FROM r WHERE id < 3) SELECT id, nope FROM r"
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), analyze(sql).problems())
    }

    @Test
    fun pipeStagesNameWhatComputesAColumn() {
        val sql = "FROM events() |> EXTEND id + 1 AS next |> AGGREGATE count(*) AS n GROUP BY next |> WHERE n > 0 AND next > 0"
        val scope = analyze(sql)
        assertEquals("AGGREGATE", scope.column(sql, "n", 1).columns.single().computedBy)
        assertEquals("EXTEND", scope.column(sql, "next", 2).columns.single().computedBy)
        assertEquals("BIGINT", scope.column(sql, "n", 1).columns.single().sqlType)
    }

    @Test
    fun aSetTargetAndDroppedColumnsAreReferencesToTheInput() {
        val sql = "FROM events() |> SET id = id + 1 |> DROP eventKind, nope |> WHERE id > 0"
        val scope = analyze(sql)
        assertEquals(id, scope.column(sql, "id").columns.single().source?.column)
        assertEquals(kind, scope.column(sql, "eventKind").columns.single().source?.column)
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), scope.problems())
        assertEquals("SET", scope.column(sql, "id", 2).columns.single().computedBy)
    }

    @Test
    fun stagesExecutionRefusesAreReported() {
        val sql = "FROM events() |> LIMIT :n |> RENAME events.id AS key |> WHERE key > 0"
        val scope = analyze(sql)
        assertEquals(listOf("LIMIT", "RENAME"), scope.diagnostics.map { sql.substring(it.start, it.end) })
        assertTrue(scope.diagnostics.all { it.problem == ScopeProblem.PIPE_UNSUPPORTED })
        assertEquals(id, scope.column(sql, "key", 1).columns.single().source?.column)
    }

    @Test
    fun completionOffersTheScopeWithInsertableNames() {
        val sql = "SELECT e. FROM events() e"
        val completion = RelationScope.complete(sql, "postgres", facts, sql.indexOf('.') + 1)
        assertEquals(PositionKind.COLUMN, completion.position)
        assertEquals(Knowledge.FULL, completion.knowledge)
        assertEquals(listOf("id", "\"eventKind\""), completion.items.map { it.insertText })
        assertEquals("column (events)", completion.items.first().detail)
        assertEquals("kotlin.Long", completion.items.first().type)
        val duckdb = RelationScope.complete(sql, "duckdb", facts, sql.indexOf('.') + 1)
        assertEquals(listOf("id", "eventKind"), duckdb.items.map { it.insertText })
        val reserved = RelationFacts(slots = mapOf("s" to RelationFact(listOf(ColumnFact("order")))))
        assertEquals(listOf("\"order\""), RelationScope.complete("SELECT  FROM s()", "duckdb", reserved, 7).items.map { it.insertText })
    }

    @Test
    fun completionReplacesTheTypedPrefix() {
        val sql = "FROM events() |> WHERE eve"
        val completion = RelationScope.complete(sql, "duckdb", facts, sql.length)
        assertEquals(sql.length - 3, completion.start)
        assertEquals(sql.length, completion.end)
        assertTrue(completion.items.all { it.start == completion.start && it.end == completion.end })
    }

    @Test
    fun completionRecoversFromIncompleteInput() {
        fun at(sql: String, caret: String = "<caret>") = RelationScope.complete(sql.replace(caret, ""), "duckdb", facts, sql.indexOf(caret))
        assertEquals(listOf("id", "eventKind"), at("SELECT CASE WHEN <caret> FROM events()").items.map { it.name })
        assertEquals(listOf("id", "eventKind"), at("FROM events() |> EXTEND coalesce(<caret>").items.map { it.name })
        assertEquals(listOf("id", "eventKind"), at("SELECT * FROM events() WHERE id = <caret> AND").items.map { it.name })
        assertEquals(PositionKind.RELATION, at("SELECT * FROM <caret>").position)
        assertEquals(PositionKind.STRING, at("SELECT '<caret>' FROM events()").position)
        assertEquals(PositionKind.STRING, at("SELECT 1 -- <caret>\nFROM events()").position)
        assertEquals(PositionKind.OTHER, at("FROM events() |> WH<caret>").position)
        assertNull(at("FROM events() |> WH<caret>").knowledge)
    }

    @Test
    fun resultsAreSerializableData() {
        val scope = analyze("SELECT e.id, nope FROM events() e")
        assertEquals(scope, Json.decodeFromString<RelationScope>(Json.encodeToString(scope)))
        val completion = RelationScope.complete("SELECT  FROM events()", "duckdb", facts, 7)
        assertEquals(completion, Json.decodeFromString<ScopeCompletion>(Json.encodeToString(completion)))
    }

    @Test
    fun cancellationPropagatesTheExactThrowable() {
        class Stop : RuntimeException()
        val stop = Stop()
        val sql = "SELECT id FROM events()"
        assertSame(stop, assertFailsWith<Stop> { RelationScope.analyze(sql, "duckdb", facts) { throw stop } })
        assertSame(stop, assertFailsWith<Stop> { RelationScope.complete(sql, "duckdb", facts, 7) { throw stop } })
    }

    @Test
    fun regionsAndCaretsOutsideTheScriptAreRejected() {
        assertFailsWith<IllegalArgumentException> { RelationScope.analyze("SELECT 1", "duckdb", facts, 0..20) }
        assertFailsWith<IllegalArgumentException> { RelationScope.complete("SELECT 1", "duckdb", facts, 9) }
    }
}
