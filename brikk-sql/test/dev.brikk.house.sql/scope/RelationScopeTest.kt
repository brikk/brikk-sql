package dev.brikk.house.sql.scope

import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
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
        val warning = analyze("SELECT nope FROM partial()").diagnostics.single()
        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
        assertEquals(SqlDiagnostic(DiagnosticSeverity.WARNING, warning.message, warning.start, warning.end, DiagnosticSource.SCOPE), warning.toSqlDiagnostic())
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
    fun aNameSeveralTablesAnswerToIsLookedUpInTheSearchPath() {
        fun table(only: String) = RelationFact(listOf(
            ColumnFact("id", "INTEGER", origin = ColumnOrigin.PHYSICAL_TABLE), ColumnFact(only, "INTEGER", origin = ColumnOrigin.PHYSICAL_TABLE),
        ))
        val two = RelationFacts(tables = mapOf("shop.a_db.orders" to table("a_only"), "shop.b_db.orders" to table("b_only")), catalog = CatalogKnowledge.CLOSED)
        fun problems(sql: String, vararg path: String) =
            RelationScope.analyze(sql, "postgres", two.copy(searchPath = path.toList())).diagnostics.map { sql.substring(it.start, it.end) }
        assertEquals(emptyList(), problems("SELECT b_only FROM orders", "shop.b_db"))
        assertEquals(listOf("a_only"), problems("SELECT a_only FROM orders", "shop.b_db"))
        assertEquals(listOf("b_only"), problems("SELECT b_only FROM orders", "a_db"))
        // The first namespace holding the table decides; one holding neither is skipped.
        assertEquals(emptyList(), problems("SELECT b_only FROM orders", "other", "b_db", "a_db"))
        // No namespace picks one: neither a first match nor a missing table, just unknown.
        assertEquals(emptyList(), problems("SELECT b_only, a_only, nope FROM orders"))
        val ambiguous = RelationScope.analyze("SELECT id FROM orders", "postgres", two).references.single { it.kind == ReferenceKind.TABLE }
        assertEquals(Resolution.AMBIGUOUS, ambiguous.resolution)
        assertEquals(Knowledge.UNKNOWN, ambiguous.knowledge)
        // A qualified name needs no search path.
        assertEquals(emptyList(), problems("SELECT a_only FROM a_db.orders", "shop.b_db"))
        val completion = RelationScope.complete("SELECT  FROM orders", "postgres", two.copy(searchPath = listOf("shop.a_db")), 7)
        assertEquals(listOf("id", "a_only"), completion.items.map { it.name })
    }

    @Test
    fun qualifiersMatchTheWholeTableName() {
        val two = RelationFacts(tables = mapOf(
            "one.t" to RelationFact(listOf(ColumnFact("id", provenance = "one"))),
            "two.t" to RelationFact(listOf(ColumnFact("id", provenance = "two"))),
            "db.v" to RelationFact(listOf(ColumnFact("a"))),
        ))
        val sql = "SELECT two.t.id, one.t.id, t.id FROM one.t CROSS JOIN two.t"
        val scope = analyze(sql, input = two)
        assertEquals("two", scope.column(sql, "id").columns.single().provenance)
        assertEquals("two.t", scope.column(sql, "id").columns.single().relation)
        assertEquals("one", scope.column(sql, "id", 1).columns.single().provenance)
        assertEquals(Resolution.AMBIGUOUS, scope.column(sql, "id", 2).resolution)
        assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), analyze("SELECT other.v.a FROM db.v", input = two).problems())
        assertEquals(emptyList(), analyze("SELECT db.v.a, v.a FROM v", input = two).problems())
        assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), analyze("SELECT db.v.a FROM db.v x", input = two).problems())
    }

    @Test
    fun aFullJoinKeyIsNotNullOnlyWhenBothSidesAreNot() {
        fun key(left: Nullability, right: Nullability): Nullability {
            val input = RelationFacts(slots = mapOf(
                "a" to RelationFact(listOf(ColumnFact("id", nullability = left))),
                "b" to RelationFact(listOf(ColumnFact("id", nullability = right))),
            ))
            return checkNotNull(analyze("SELECT id FROM a() FULL JOIN b() USING (id)", input = input).output).columns.single().nullability
        }
        assertEquals(Nullability.NON_NULL, key(Nullability.NON_NULL, Nullability.NON_NULL))
        assertEquals(Nullability.NULLABLE, key(Nullability.NON_NULL, Nullability.NULLABLE))
        assertEquals(Nullability.UNKNOWN, key(Nullability.NON_NULL, Nullability.UNKNOWN))
        val typed = RelationFacts(slots = listOf("a", "b").associateWith { RelationFact(listOf(ColumnFact("id", "BIGINT", "kotlin.Long"))) })
        val merged = checkNotNull(analyze("SELECT id FROM a() FULL JOIN b() USING (id)", input = typed).output).columns.single()
        assertEquals("BIGINT" to "kotlin.Long", merged.sqlType to merged.displayType)
    }

    @Test
    fun factTypesAndComputedTypesShareOneSpelling() {
        val input = RelationFacts(slots = mapOf("n" to RelationFact(listOf(ColumnFact("id", "int4"), ColumnFact("odd", "not a type")))))
        val union = analyze("SELECT id FROM n() UNION ALL SELECT id + 1 FROM n()", "postgres", input).output
        assertEquals("INT", union?.columns?.single()?.sqlType)
        assertEquals("not a type", analyze("SELECT odd FROM n()", "postgres", input).output?.columns?.single()?.sqlType)
    }

    @Test
    fun declarationsLocateWhereSqlNamesRelationsAndColumns() {
        val sql = "WITH q AS (SELECT id AS k, id + 1 AS n FROM events()) SELECT x.k, x.n FROM q x |> RENAME k AS key |> WHERE key > 0"
        val scope = analyze(sql)
        fun range(text: String, nth: Int = 0) = Regex("\\b$text\\b").findAll(sql).elementAt(nth).range.let { ScopeRange(it.first, it.last + 1) }
        val cte = scope.references.single { it.kind == ReferenceKind.TABLE && it.start == range("q").start }
        assertEquals(range("q"), cte.relation?.declaration)
        assertEquals(range("x", 2), scope.references.first { it.kind == ReferenceKind.QUALIFIER }.relation?.declaration)
        assertEquals(range("k"), scope.column(sql, "k", 1).columns.single().declaration)
        assertEquals(range("n"), scope.column(sql, "n", 1).columns.single().declaration)
        assertEquals(range("key"), scope.column(sql, "key", 1).columns.single().declaration)
        assertNull(scope.column(sql, "id").columns.single().declaration)
    }

    @Test
    fun columnsSayWhichKindOfRelationTheyAreSeenThroughAndCameFrom() {
        val input = facts.copy(calls = mapOf("tvf" to RelationFact(listOf(ColumnFact("v")))))
        val sql = "WITH q AS (SELECT id FROM events()) SELECT e.id, a.id, q.id, d.id, t.v, d.id + 1 AS n " +
            "FROM events() e, accounts a, q, (SELECT id FROM events()) d, tvf(1) t"
        val scope = analyze(sql, input = input)
        fun seen(nth: Int, name: String = "id") = scope.column(sql, name, nth).columns.single().let { it.relationKind to it.source?.kind }
        assertEquals(RelationKind.SLOT to RelationKind.SLOT, seen(1))
        assertEquals(RelationKind.TABLE to RelationKind.TABLE, seen(2))
        assertEquals(RelationKind.CTE to RelationKind.SLOT, seen(3))
        assertEquals(RelationKind.SUBQUERY to RelationKind.SLOT, seen(4))
        assertEquals(RelationKind.CALL to RelationKind.CALL, seen(0, "v"))
        val ordered = "SELECT id + 1 AS n FROM events() ORDER BY n"
        assertEquals(null, analyze(ordered).column(ordered, "n", 1).columns.single().relationKind)
    }

    @Test
    fun slotsMatchExactlyAndOnlyWithoutArgumentsOrQualifiers() {
        assertTrue(analyze("SELECT e.nope FROM Events() e").diagnostics.isEmpty())
        assertTrue(analyze("SELECT e.nope FROM events(1) e").diagnostics.isEmpty())
        assertTrue(analyze("SELECT e.nope FROM db.events() e").diagnostics.isEmpty())
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), analyze("SELECT e.nope FROM events() e").problems())
    }

    @Test
    fun namesCompareAsTheDialectStoresThem() {
        fun problems(sql: String, dialect: String) = analyze(sql, dialect).problems()
        assertEquals(emptyList(), problems("SELECT \"EVENTKIND\" FROM events()", "duckdb"))
        assertEquals(emptyList(), problems("SELECT `EVENTKIND` FROM events()", "doris"))
        assertEquals(emptyList(), problems("SELECT \"eventKind\" FROM events()", "postgres"))
        // Unquoted names fold on input in PostgreSQL; a provider's mixed-case name needs quotes.
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems("SELECT eventKind FROM events()", "postgres"))
        assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), problems("SELECT e.id FROM events() AS \"E\"", "postgres"))
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems("SELECT x.foo FROM (SELECT id AS \"Foo\" FROM events()) x", "postgres"))
        assertEquals(emptyList(), problems("SELECT x.FOO FROM (SELECT id AS Foo FROM events()) x", "postgres"))
        // DuckDB folds ASCII letters only.
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems("SELECT \"ä\" FROM (SELECT 1 AS \"Ä\") t", "duckdb"))
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems("SELECT EVENTKIND FROM events()", "clickhouse"))
        assertEquals(listOf(ScopeProblem.UNKNOWN_QUALIFIER), problems("SELECT E.id FROM events() e", "doris"))
    }

    @Test
    fun clausesSeeProjectionAliasesWhereTheDialectDoes() {
        fun problems(sql: String, dialect: String) = analyze(sql, dialect).problems()
        val grouped = "SELECT eventKind AS k, count(*) AS n FROM events() GROUP BY k"
        assertEquals(kind, analyze(grouped).column(grouped, "k", 1).columns.single().source?.column)
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems(grouped, "trino"))
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems(grouped, "hive"))
        assertEquals(emptyList(), problems(grouped, "spark"))
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems("SELECT count(*) AS n FROM events() GROUP BY n", "duckdb"))
        val having = "SELECT id AS k FROM events() GROUP BY id HAVING k > 0"
        assertEquals(emptyList(), problems(having, "duckdb"))
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems(having, "postgres"))
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems(having, "trino"))
        val where = "SELECT id AS k FROM events() WHERE k > 0"
        assertEquals(emptyList(), problems(where, "duckdb"))
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems(where, "postgres"))
        val lateral = "SELECT id AS k, k + 1 AS j FROM events()"
        assertEquals(emptyList(), problems(lateral, "duckdb"))
        assertEquals(emptyList(), problems(lateral, "clickhouse"))
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems(lateral, "trino"))
        assertEquals(listOf(ScopeProblem.MISSING_COLUMN), problems("SELECT k + 1 AS j, id AS k FROM events()", "duckdb"))
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
    fun completionReplacesTheWholeIdentifierInItsQuoting() {
        fun at(sql: String, dialect: String = "duckdb") =
            RelationScope.complete(sql.replace("<caret>", ""), dialect, facts, sql.indexOf("<caret>"))
        val quoted = at("SELECT \"ev<caret>\" FROM events()", "postgres")
        assertEquals("ev", quoted.prefix)
        assertEquals("\"ev\"".length, quoted.end - quoted.start)
        assertEquals(listOf("\"id\"", "\"eventKind\""), quoted.items.map { it.insertText })
        val open = at("SELECT \"ev<caret> FROM events()", "postgres")
        assertEquals("ev", open.prefix)
        assertEquals(listOf("id", "eventKind"), open.items.map { it.name })
        val middle = at("SELECT i<caret>d FROM events()")
        assertEquals("i", middle.prefix)
        assertEquals("SELECT ".length to "SELECT id".length, middle.start to middle.end)
    }

    @Test
    fun completionKnowsCommentsAndStringsByTheDialectsLexing() {
        fun at(sql: String, dialect: String) = RelationScope.complete(sql.replace("<caret>", ""), dialect, facts, sql.indexOf("<caret>")).position
        assertEquals(PositionKind.COMMENT, at("SELECT 1 # <caret>\nFROM events()", "mysql"))
        assertEquals(PositionKind.COMMENT, at("SELECT /* a /* b */ <caret> */ id FROM events()", "postgres"))
        assertEquals(PositionKind.COMMENT, at("SELECT /* <caret>", "duckdb"))
        assertEquals(PositionKind.STRING, at("SELECT 'a<caret>", "duckdb"))
        assertEquals(PositionKind.COLUMN, at("SELECT <caret> FROM events() # x", "mysql"))
    }

    @Test
    fun droppedRenamedAndExcludedColumnsComplete() {
        fun at(sql: String) = RelationScope.complete(sql.replace("<caret>", ""), "duckdb", facts, sql.indexOf("<caret>")).items.map { it.name }
        assertEquals(listOf("id", "eventKind"), at("FROM events() |> DROP <caret>"))
        assertEquals(listOf("id", "eventKind"), at("FROM events() |> RENAME <caret>"))
        assertEquals(listOf("id", "eventKind"), at("FROM events() |> RENAME id AS k, <caret>"))
        assertEquals(listOf("id", "eventKind"), at("SELECT * EXCLUDE (<caret>) FROM events()"))
    }

    @Test
    fun relationPositionsOfferCtesTablesSlotsAndTableFunctions() {
        val input = facts.copy(calls = mapOf("read_rows" to RelationFact(knowledge = Knowledge.UNKNOWN)))
        fun at(sql: String) = RelationScope.complete(sql.replace("<caret>", ""), "duckdb", input, sql.indexOf("<caret>"))
        val from = at("WITH recent AS (SELECT id FROM events()) SELECT * FROM recent r JOIN <caret>")
        assertEquals(PositionKind.RELATION, from.position)
        assertEquals(listOf("recent", "db.accounts", "events()", "partial()", "unknown()", "read_rows()"), from.items.map { it.insertText })
        assertEquals(listOf(RelationKind.CTE, RelationKind.TABLE, RelationKind.SLOT, RelationKind.SLOT, RelationKind.SLOT, RelationKind.CALL),
            from.items.map { it.relation?.kind })
        assertTrue(from.items.all { it.kind == CompletionKind.RELATION && it.column == null })
        assertEquals(listOf("accounts"), at("SELECT * FROM db.<caret>").items.map { it.insertText })
        assertEquals(listOf("db.accounts", "events()", "partial()", "unknown()", "read_rows()"), at("SELECT * FROM ev<caret>").items.map { it.insertText })
    }

    @Test
    fun completionRecoversFromIncompleteInput() {
        fun at(sql: String, caret: String = "<caret>") = RelationScope.complete(sql.replace(caret, ""), "duckdb", facts, sql.indexOf(caret))
        assertEquals(listOf("id", "eventKind"), at("SELECT CASE WHEN <caret> FROM events()").items.map { it.name })
        assertEquals(listOf("id", "eventKind"), at("FROM events() |> EXTEND coalesce(<caret>").items.map { it.name })
        assertEquals(listOf("id", "eventKind"), at("SELECT * FROM events() WHERE id = <caret> AND").items.map { it.name })
        assertEquals(PositionKind.RELATION, at("SELECT * FROM <caret>").position)
        assertEquals(PositionKind.STRING, at("SELECT '<caret>' FROM events()").position)
        assertEquals(PositionKind.COMMENT, at("SELECT 1 -- <caret>\nFROM events()").position)
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
