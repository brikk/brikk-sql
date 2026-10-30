package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbCopyLayoutTest {
    private val facts = RelationFacts(tables = mapOf("events" to RelationFact(listOf(
        ColumnFact("region", "VARCHAR"), ColumnFact("day", "INTEGER"), ColumnFact("v", "INTEGER"), ColumnFact("id", "INTEGER")))),
        catalog = CatalogKnowledge.CLOSED)

    @Test
    fun nativeOptionNamesListsExpressionsAndPlacementRetainTheExistingAstAndSerdeShape() {
        for (options in listOf("PARTITION_BY (region,day)", "ORDER_BY (v)",
            "ORDER_BY (abs(v) DESC NULLS LAST,id ASC NULLS FIRST),PARTITION_BY (region,day)",
            "PARTITION_BY region,FORMAT parquet,ORDER_BY (CASE WHEN v IS NULL THEN 0 ELSE v END DESC,id)",
            "PARTITION_BY ('region','day'),ORDER_BY 'v DESC NULLS FIRST'", "ORDER_BY v", "ORDER_BY (v COLLATE nocase)",
            "PARTITION BY (region,day),ORDER BY (v DESC)", "PARTITIONED_BY region")) {
            val tree = assertIs<Copy>(Dialects.DUCKDB.parseOne("COPY events TO 'out' ($options)"))
            assertEquals(tree, tree.copy())
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val rendered = tree.sql("duckdb")
            assertFalse("ORDER BY" in rendered, rendered)
            assertFalse("PARTITION BY" in rendered, rendered)
            assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
            assertTrue(SqlFragment(rendered, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        }
    }

    @Test
    fun explicitAndOmittedOrderingRemainDistinctInsteadOfFreezingSessionDefaults() {
        val tree = assertIs<Copy>(Dialects.DUCKDB.parseOne("COPY events TO 'out' (ORDER_BY (v,id ASC NULLS LAST,day DESC NULLS FIRST))"))
        val terms = assertIs<Tuple>(assertIs<CopyParameter>((tree.args["params"] as List<*>).single()).expressionArg).expressionsArg.map { assertIs<Ordered>(it) }
        assertNull(terms[0].args["desc"])
        assertEquals(false,terms[0].args["nulls_first"])
        assertEquals(true,terms[0].meta["duckdb_copy_default_null_order"])
        assertEquals(false, terms[1].args["desc"])
        assertEquals(false, terms[1].args["nulls_first"])
        assertEquals(true, terms[2].args["desc"])
        assertEquals(true, terms[2].args["nulls_first"])
        assertTrue("ORDER_BY (v, id ASC NULLS LAST, day DESC NULLS FIRST)" in tree.sql("duckdb"))
    }

    @Test
    fun orderingAndPartitionNamesResolveAgainstProjectedColumnsAndNotTheSelectNamespace() {
        val prefix = "COPY (SELECT region AS area,day,v AS value,id FROM events) TO 'out' "
        val sql = prefix + "(PARTITION_BY (area,day),ORDER_BY (abs(value) DESC NULLS LAST,id))"
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertTrue(scope.parsed)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        for (name in listOf("area", "day", "value", "id"))
            assertTrue(scope.references.any { it.name == name && it.start > sql.indexOf("PARTITION_BY") && it.resolution == Resolution.RESOLVED }, name)
        val bad = RelationScope.analyze(prefix + "(PARTITION_BY region,ORDER_BY (events.v))", "duckdb", facts)
        assertTrue(bad.diagnostics.any { it.problem == ScopeProblem.MISSING_COLUMN })
        assertTrue(bad.diagnostics.any { it.problem == ScopeProblem.UNKNOWN_QUALIFIER })
        val direct = RelationScope.analyze("COPY events TO 'out' (PARTITION_BY region,ORDER_BY (v))", "duckdb", facts)
        assertTrue(direct.diagnostics.isEmpty(), direct.diagnostics.toString())
        for (option in listOf("PARTITION_BY ", "ORDER_BY (")) {
            val completion = prefix + "($option) )"
            val caret = completion.indexOf(option) + option.length
            assertTrue(RelationScope.complete(completion, "duckdb", facts, caret).items.map { it.name }.containsAll(listOf("area", "day", "value", "id")))
        }
    }

    @Test
    fun copyCteResultShapeRemainsCountAndOptionalFilesNotTheExportedRow() {
        for ((flag, expected) in listOf("" to listOf("Count"), ",RETURN_FILES" to listOf("Count", "Files"))) {
            val sql = "WITH copied AS (COPY events TO 'out' (PARTITION_BY (region,day),ORDER_BY (v)$flag)) SELECT * FROM copied"
            val output = assertNotNull(RelationScope.analyze(sql, "duckdb", facts).output)
            assertEquals(expected, output.columns.map { it.name })
            assertTrue(output.columns.all { it.origin == ColumnOrigin.COMPUTED && it.provenance == null })
            val shape = SqlFragment(sql, "duckdb").outputShape()
            assertEquals(expected, shape.columns.map { it.name })
            assertEquals(if (flag.isEmpty()) listOf("BIGINT") else listOf("BIGINT", "ARRAY<TEXT>"), shape.columns.map { it.type })
            assertFails { SqlFragment(sql, "duckdb").columnDependencies() }
        }
    }

    @Test
    fun qualificationAndCrossTargetExecutionRefuseUnprovedLayoutTransformations() {
        val sql = "COPY (SELECT * FROM events) TO 'out' (PARTITION_BY region,ORDER_BY (v DESC))"
        assertFailsWith<OptimizeError> { qualify(Dialects.DUCKDB.parseOne(sql), dialect = Dialects.DUCKDB) }
        for (target in listOf("postgres", "trino", "doris", "clickhouse", "mysql", "bigquery")) {
            assertFailsWith<UnsupportedError>(target) { SqlFragment(sql, "duckdb").toExecutable(target) }
            assertTrue(SqlFragment(sql, "duckdb").transpileTo(target).unsupportedMessages.any { "COPY" in it })
        }
        assertFailsWith<UnsupportedError> { SqlFragment("COPY events TO 'out' (PARTITION_BY region)", "postgres").toExecutable("duckdb") }
    }

    @Test
    fun invalidOptionBoundariesOrderingPlacementAndDuplicateNamesAreNotCommands() {
        for (options in listOf("ORDER_BY ()", "ORDER_BY", "ORDER_BY v DESC", "PARTITION_BY (region DESC)",
            "ORDER_BY (v),ORDER_BY (id)", "PARTITION_BY region,PARTITIONED_BY day", "ORDER_BY (v) FORMAT parquet", "ORDER_BY (v),"))
            assertFailsWith<ParseError>(options) { Dialects.DUCKDB.parseOne("COPY events TO 'out' ($options)") }
        val text = "COPY events TO 'a;b' (PARTITION_BY (region,day),ORDER_BY (v DESC)); SELECT 42;"
        val statements = SqlScriptSnapshot.parse(text, "duckdb").statements
        assertEquals(2, statements.count { it.hasSql })
        assertTrue(statements.all { it.boundaryError == null })
        val bound = "COPY (SELECT region,day,v+? AS value FROM events WHERE id>?) TO (?) (PARTITION_BY (region,day),ORDER_BY (value*?))"
        val fragment = SqlFragment(bound, "duckdb")
        assertEquals(4, fragment.scalarParams.sumOf { it.count })
        assertEquals(4, fragment.toExecutable("duckdb").sql.count { it == '?' })
        val occurrences = parseParameterOccurrences(bound,"duckdb")
        assertEquals(bound.indices.filter { bound[it]=='?' },occurrences.map { it.range.start })
        assertTrue(occurrences.all { bound.substring(it.range.start,it.range.end)=="?" })
    }

    @Test
    fun opaqueLayoutScopeAndMalformedOrderingTermsRefuseInsteadOfInventingOrDroppingFacts() {
        for (option in listOf("ORDER_BY 'v DESC'", "PARTITION_BY ('region','day')", "PARTITION_BY getvariable('keys')", "PARTITION_BY events.region"))
            assertFailsWith<UnsupportedError> { RelationScope.analyze("COPY events TO 'out' ($option)","duckdb",facts) }
        val named = "COPY (SELECT v+\$amount AS value FROM events) TO (\$file) (ORDER_BY (value*\$factor))"
        assertEquals(listOf("amount","file","factor"),parseParameterOccurrences(named,"duckdb").map { it.name })
        val fragment = SqlFragment("COPY events TO 'out' (ORDER_BY (v,id))","duckdb")
        val tree = assertIs<Copy>(fragment.ast)
        val value = assertIs<Tuple>(assertIs<CopyParameter>((tree.args["params"] as List<*>).single()).expressionArg)
        value.set("expressions",listOf(value.expressionsArg.first(),Literal.number(42)))
        assertFailsWith<UnsupportedError> { fragment.toExecutable("duckdb") }
    }
}
