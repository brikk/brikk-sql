package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbExplainTest {
    private val examples = listOf("EXPLAIN SELECT 1", "EXPLAIN ANALYZE SELECT 1", "EXPLAIN ANALYSE SELECT 1",
        "EXPLAIN (SQL) SELECT 1+2 AS answer", "EXPLAIN (FORMAT JSON) SELECT * FROM data",
        "EXPLAIN (FORMAT 'json') VALUES(1)", "EXPLAIN (FORMAT foo.json) SELECT 1", "EXPLAIN (FORMAT ('json')) SELECT 1",
        "EXPLAIN (FORMAT DEFAULT) SELECT 1", "EXPLAIN (ANALYZE false, FORMAT compact) SELECT 1",
        "EXPLAIN (ANALYSE, ANALYZE 0) SELECT 1", "EXPLAIN ANALYZE (FORMAT yaml) SELECT 1",
        "EXPLAIN (SQL) WITH c AS MATERIALIZED (SELECT id FROM data) SELECT * FROM c",
        "EXPLAIN (SQL) INSERT INTO target VALUES(1)", "EXPLAIN CREATE TABLE target(id INT)")

    @Test
    fun structuredOptionsAndChildStatementsSurviveCopySerdeAndGeneration() {
        for (sql in examples) {
            val tree = assertIs<DuckdbExplain>(Dialects.DUCKDB.parseOne(sql), sql)
            assertTrue(tree.explainProblems().isEmpty(), tree.explainProblems().toString())
            for (copy in listOf(tree.copy(), assertIs<Expression>(Serde.load(Serde.dump(tree))))) {
                assertEquals(tree, copy)
                val rendered = copy.sql("duckdb")
                assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
            }
            assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        }
        assertEquals("brikk.duckdb", ExpressionRegistry.entries.getValue("DuckdbExplain").module)
        assertEquals("brikk.duckdb", ExpressionRegistry.entries.getValue("DuckdbExplainOption").module)
    }

    @Test
    fun analyzePresenceEnablesExecutionAndItsAuthoredArgumentsAreNotDroppedOrReinterpreted() {
        for (sql in listOf("EXPLAIN (ANALYZE false) INSERT INTO target VALUES(1)",
            "EXPLAIN (ANALYZE nextval('ignored')) SELECT 1", "EXPLAIN (ANALYSE 0, ANALYZE) SELECT 1")) {
            val tree = assertIs<DuckdbExplain>(Dialects.DUCKDB.parseOne(sql))
            assertTrue(tree.analyzesChild())
            assertTrue(tree.explainProblems().isEmpty())
            assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").sql.contains("ANALY", true))
        }
        val tree = assertIs<DuckdbExplain>(Dialects.DUCKDB.parseOne("EXPLAIN (ANALYZE false) SELECT 1"))
        assertIs<dev.brikk.house.sql.ast.Boolean>(assertIs<DuckdbExplainOption>(tree.expressionsArg.single()).args["expression"])
        val params = SqlFragment("EXPLAIN (ANALYZE ?) SELECT ? AS value", "duckdb")
        assertEquals(2, params.scalarParams.sumOf { it.count })
        assertFailsWith<UnsupportedError> { params.toExecutable("duckdb") }
    }

    @Test
    fun optionSemanticsRefuseInvalidOrUnmodeledExecutionWithoutClaimingAGrammarError() {
        for (sql in listOf("EXPLAIN (SQL true) SELECT 1", "EXPLAIN (SQL,SQL) SELECT 1", "EXPLAIN ANALYZE (SQL) SELECT 1",
            "EXPLAIN (SQL, ANALYZE false) SELECT 1", "EXPLAIN (SQL,FORMAT default) SELECT 1",
            "EXPLAIN (FORMAT json,FORMAT yaml) SELECT 1", "EXPLAIN (FORMAT) SELECT 1", "EXPLAIN (FORMAT 1+1) SELECT 1",
            "EXPLAIN (FORMAT NULL) SELECT 1", "EXPLAIN (FORMAT bogus) SELECT 1", "EXPLAIN (FORMAT web) SELECT 1",
            "EXPLAIN (WHATEVER) SELECT 1", "EXPLAIN (SQL) CREATE TABLE target(id INT)", "EXPLAIN (SQL) CALL range(3)")) {
            assertIs<DuckdbExplain>(Dialects.DUCKDB.parseOne(sql), sql)
            assertFailsWith<UnsupportedError>(sql) { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
            assertTrue(SqlFragment(sql, "duckdb").transpileTo("duckdb").unsupportedMessages.isNotEmpty())
            assertFailsWith<ShapeError> { SqlFragment(sql, "duckdb").outputShape() }
        }
    }

    @Test
    fun malformedGrammarAndNonExplainableStatementFamiliesDoNotBecomeRawExplainCommands() {
        for (sql in listOf("EXPLAIN", "EXPLAIN () SELECT 1", "EXPLAIN (FORMAT=json) SELECT 1", "EXPLAIN (SQL", "EXPLAIN 'SELECT 1'",
            "EXPLAIN EXPLAIN SELECT 1", "EXPLAIN CONNECT remote", "EXPLAIN ATTACH ':memory:' AS db",
            "EXPLAIN CREATE EXTERNAL RESOURCE 'fixture@local' AS r", "EXPLAIN CREATE EXTENSION REPOSITORY r PREFIX '/p'"))
            assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
        val raw = SqlFragment("EXPLAIN RESET memory_limit", "duckdb")
        assertIs<DuckdbExplain>(raw.ast)
        assertFailsWith<UnsupportedError> { raw.toExecutable("duckdb") }
        assertFailsWith<ShapeError> { raw.sourceTables }
    }

    @Test
    fun scopesInspectOnlyTheChildAndReturnPlanTextRatherThanTheChildsResultOrFormatColumns() {
        val sql = "EXPLAIN (FORMAT foo.json) WITH c AS (SELECT id FROM data) SELECT id FROM c WHERE id>0"
        val facts = RelationFacts(tables = mapOf("data" to RelationFact(listOf(ColumnFact("id", "INT")))), catalog = CatalogKnowledge.CLOSED)
        val scope = RelationScope.analyze(sql, "duckdb", facts)
        assertTrue(scope.parsed)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        assertEquals(listOf("explain_key", "explain_value"), assertNotNull(scope.output).columns.map { it.name })
        assertTrue(scope.output.columns.all { it.sqlType == "VARCHAR" && it.origin == ColumnOrigin.COMPUTED && it.provenance == null })
        assertTrue(scope.references.none { it.columns.any { column -> column.name == "json" } })
        assertEquals(listOf("data"), SqlFragment(sql, "duckdb").sourceTables)
        assertEquals(listOf("VARCHAR", "VARCHAR"), SqlFragment(sql, "duckdb").outputShape().columns.map { it.type })
        val tree = Dialects.DUCKDB.parseOne(sql)
        assertFailsWith<OptimizeError> { qualify(tree, dialect = Dialects.DUCKDB) }
        assertFailsWith<OptimizeError> { annotateTypes(tree, dialect = Dialects.DUCKDB) }
        assertFailsWith<SqlglotError> { lineageAll(sql, dialect = Dialects.DUCKDB) }
        val prepared = "PREPARE p AS EXPLAIN (ANALYZE (SELECT missing FROM hidden)) SELECT id FROM data"
        assertTrue(RelationScope.analyze(prepared, "duckdb", facts).diagnostics.isEmpty())
        assertEquals(listOf("data"), SqlFragment(prepared, "duckdb").sourceTables)
    }

    @Test
    fun scriptBoundariesParametersRefusalsAndExistingEditorExplainSafetyRemainIntact() {
        val sql = "EXPLAIN (SQL) SELECT ';' AS value; EXPLAIN ANALYZE SELECT 1; SELECT 2;"
        assertEquals(3, SqlScriptSnapshot.parse(sql, "duckdb").statements.count { it.hasSql })
        assertEquals(3, Dialects.DUCKDB.parse(sql).filterNotNull().filterNot { it is Semicolon }.size)
        val params = SqlFragment("EXPLAIN (SQL) SELECT \$first AS a,? AS b,\$last AS c", "duckdb")
        assertEquals(listOf("first", null, "last"), params.scalarParams.map { it.name })
        assertEquals(params.scalarParams.map { it.name }, SqlFragment(params.toExecutable("duckdb").sql, "duckdb").scalarParams.map { it.name })
        for (target in listOf("postgres", "trino", "doris", "clickhouse", "mysql"))
            assertFailsWith<UnsupportedError> { SqlFragment("EXPLAIN (SQL) SELECT 1", "duckdb").toExecutable(target) }
        assertIs<ExplainPreparation.Refused>(prepareTextExplain("SELECT * FROM range(nextval('s'))", "duckdb"))
        assertTrue(mayContainDuckdbExplain("/* comment */ EXPLAIN SELECT 1"))
        assertFalse(mayContainDuckdbExplain("SELECT 'EXPLAIN',\"EXPLAIN\""))
    }
}
