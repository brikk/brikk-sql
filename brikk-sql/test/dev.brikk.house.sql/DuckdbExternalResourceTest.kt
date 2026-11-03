package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.mayContainDuckdbExternalResources
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbExternalResourceTest {
    private val secret = "dummy-resource-credential"
    private val create = "CREATE EXTERNAL RESOURCE 'fixture@local' AS \"Case Name\" (SPOT, REGION 'eu-west-1', TOKEN '$secret')"
    private val register = "REGISTER EXTERNAL RESOURCE 'fixture@local' AS \"Case Name\" FROM MAP {'id':'local','token':'$secret'}"
    private val statements = listOf(create, register, "DESTROY EXTERNAL RESOURCE \"he said \"\"hi\"\"\"",
        "SHOW EXTERNAL RESOURCES", "SHOW ALL EXTERNAL RESOURCES",
        "ATTACH TO EXTERNAL RESOURCE \"Case Name\" AS \"select\" (READ_ONLY)",
        "ATTACH TO NEW TEMPORARY EXTERNAL RESOURCE 'fixture@local' (REGION 'x', SPOT) AS db (READ_ONLY, TOKEN '$secret')",
        "CONNECT TO EXTERNAL RESOURCE \"Case Name\" (TOKEN '$secret')",
        "CONNECT TO NEW TEMPORARY EXTERNAL RESOURCE 'fixture@local' (REGION 'x')")

    @Test
    fun allStatementFormsRemainStructuredAcrossCopySerdeAndRegeneration() {
        for (sql in statements + listOf("CREATE EXTERNAL RESOURCE 'type'", "REGISTER EXTERNAL RESOURCE 'type' FROM {'id':'x'}")) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertTrue(tree.isDuckdbExternalResourceStatement(), sql)
            for (copy in listOf(tree.copy(), assertIs<Expression>(Serde.load(Serde.dump(tree))))) {
                assertEquals(tree, copy)
                val rendered = copy.sql("duckdb")
                assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
            }
        }
        for (sql in statements) assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        for (name in listOf("DuckdbCreateExternalResource", "DuckdbRegisterExternalResource", "DuckdbDestroyExternalResource",
            "DuckdbShowExternalResources", "DuckdbExternalResourceSource", "DuckdbAttachExternalResource", "DuckdbConnectExternalResource"))
            assertEquals("brikk.duckdb", ExpressionRegistry.entries.getValue(name).module)
    }

    @Test
    fun sourceOwnershipAndSeparateCreationVersusAttachmentOptionBagsArePreserved() {
        val tree = assertIs<DuckdbAttachExternalResource>(Dialects.DUCKDB.parseOne(statements[6]))
        val source = assertIs<DuckdbExternalResourceSource>(tree.thisArg)
        assertEquals(true, source.args["temporary"])
        assertEquals(listOf("REGION", "SPOT"), source.expressionsArg.filterIsInstance<AttachOption>().map { assertIs<Expression>(it.thisArg).name })
        assertEquals(listOf("READ_ONLY", "TOKEN"), tree.expressionsArg.filterIsInstance<AttachOption>().map { assertIs<Expression>(it.thisArg).name })
        val reference = assertIs<DuckdbExternalResourceSource>(Dialects.DUCKDB.parseOne(statements[5]).thisArg)
        assertEquals(false, reference.args["temporary"])
        assertTrue(assertIs<Identifier>(reference.thisArg).quoted)
        val connection = assertIs<DuckdbConnectExternalResource>(Dialects.DUCKDB.parseOne(statements[7]))
        assertTrue(assertIs<DuckdbExternalResourceSource>(connection.thisArg).expressionsArg.isEmpty())
        assertEquals(1, connection.expressionsArg.size)
        assertTrue(SqlFragment(register, "duckdb").sourceTables.isEmpty())
        assertTrue(SqlFragment(register, "duckdb").scalarParams.isEmpty())
        val parameters = SqlFragment("REGISTER EXTERNAL RESOURCE 'fixture@local' AS r FROM ?", "duckdb").scalarParams
        assertEquals(1, parameters.sumOf { it.count })
        val bags = SqlFragment("ATTACH TO NEW TEMPORARY EXTERNAL RESOURCE 'fixture@local' (REGION \$region) AS db (TOKEN \$token)", "duckdb")
        val emitted = bags.toExecutable("duckdb").sql
        assertEquals(listOf("region", "token"), bags.scalarParams.map { it.name })
        assertEquals(listOf("region", "token"), SqlFragment(emitted, "duckdb").scalarParams.map { it.name })
        val authored = Dialects.DUCKDB.parseOne(register)
        assertTrue(secret in normalizeIdentifiers(authored.copy(), dialect = Dialects.DUCKDB).sql("duckdb"))
        assertFailsWith<ShapeError> { SqlFragment("REGISTER EXTERNAL RESOURCE 't' AS r FROM (SELECT h FROM inputs)", "duckdb").sourceTables }
    }

    @Test
    fun nativeGrammarLimitsDoNotCollapseOptionsOrBecomeOpaqueCommands() {
        for (sql in listOf("CREATE OR REPLACE EXTERNAL RESOURCE 't' AS r", "CREATE TEMP EXTERNAL RESOURCE 't' AS r",
            "CREATE EXTERNAL RESOURCE type AS r", "REGISTER EXTERNAL RESOURCE 't' AS r", "REGISTER EXTERNAL RESOURCE 't' AS r FROM",
            "DESTROY EXTERNAL RESOURCE 'r'", "ATTACH TO EXTERNAL RESOURCE r", "ATTACH TO EXTERNAL RESOURCE r (X 1) AS db",
            "ATTACH TO NEW EXTERNAL RESOURCE 't' AS db", "CONNECT TO NEW TEMPORARY EXTERNAL RESOURCE 't' (X 1) (Y 2)",
            "CONNECT TO EXTERNAL RESOURCE r AS db", "CREATE EXTERNAL RESOURCE 't' AS r (X=1)",
            "CREATE EXTERNAL RESOURCE 't' AS r (X 1,x 2)", "CREATE EXTERNAL RESOURCE 't' AS r ()",
            "SHOW ALL EXTERNAL RESOURCE", "SHOW EXTERNAL RESOURCES extra")) {
            assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
        }
        for (sql in listOf("CREATE EXTERNAL RESOURCE 't'", "REGISTER EXTERNAL RESOURCE 't' FROM MAP {'id':'x'}"))
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
        assertIs<Literal>(Dialects.DUCKDB.parseOne("'REGISTER'"))
        assertIs<Column>(Dialects.DUCKDB.parseOne("\"DESTROY\""))
    }

    @Test
    fun managementHasNoInventedColumnScopeWhileShowHasAnOpaqueRuntimeHandleContract() {
        val facts = RelationFacts(catalog = CatalogKnowledge.CLOSED)
        for (sql in statements) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.parsed, sql)
            assertTrue(scope.references.isEmpty())
            assertTrue(scope.diagnostics.isEmpty())
            if (tree is DuckdbShowExternalResources) {
                val row = assertNotNull(scope.output)
                assertEquals(listOf("name", "type", "managed", "state", "reference", "uri", "attached_db_type", "handle"), row.columns.map { it.name })
                assertEquals("MAP(VARCHAR, VARCHAR)", row.columns.last().sqlType)
                assertTrue(row.columns.all { it.origin == ColumnOrigin.COMPUTED && it.provenance == null })
                assertEquals(if (tree.args["all"] == true) Nullability.NULLABLE else Nullability.NON_NULL, row.columns.first().nullability)
                assertEquals(8, SqlFragment(sql, "duckdb").outputShape().columns.size)
            } else {
                assertNull(scope.output)
                assertFailsWith<ShapeError> { SqlFragment(sql, "duckdb").outputShape() }
            }
            val before = Serde.dump(tree)
            assertFailsWith<OptimizeError> { qualify(tree, dialect = Dialects.DUCKDB) }
            assertEquals(before, Serde.dump(tree))
            assertFailsWith<OptimizeError> { annotateTypes(tree, dialect = Dialects.DUCKDB) }
            assertFailsWith<SqlglotError> { lineageAll(sql, dialect = Dialects.DUCKDB) }
        }
    }

    @Test
    fun diagnosticsAndNoBindGuardsDoNotLeakHandlesTokensOrProviderStrings() {
        for (sql in listOf("$create invalid extra", "$register trailing extra", "CREATE EXTERNAL RESOURCE 'https://$secret",
            "SHOW EXTERNAL RESOURCES '$secret'", "CONNECT TO NEW TEMPORARY EXTERNAL RESOURCE '$secret' (X 1) (Y 2)")) {
            val error = runCatching { Dialects.DUCKDB.parseOne(sql) }.exceptionOrNull() ?: fail("Expected invalid external-resource input")
            assertTrue(error is ParseError || error is TokenError)
            assertFalse(secret in error.toString())
            assertNull(error.cause)
            if (error is ParseError) for (info in error.errors)
                assertFalse(listOf(info.description, info.startContext, info.highlight, info.endContext).any { it?.contains(secret) == true })
            assertFalse(SqlScriptSnapshot.parse(sql, "duckdb").statements.any { it.boundaryError?.contains(secret) == true })
        }
        for (sql in statements) assertTrue(mayContainDuckdbExternalResources(sql))
        assertTrue(mayContainDuckdbExternalResources("CALL register_external_resource_type('fixture')"))
        assertTrue(mayContainDuckdbExternalResources("CALL \"register_external_resource_type\"('fixture')"))
        assertFalse(mayContainDuckdbExternalResources("SELECT 'EXTERNAL RESOURCE',\"create_external_resource\" FROM t -- EXTERNAL RESOURCES"))
    }

    @Test
    fun crossTargetExecutionRefusesAndScriptBoundariesKeepWholeHandleExpressions() {
        for (sql in statements) for (target in listOf("postgres", "trino", "doris", "clickhouse", "mysql")) {
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable(target) }
            val preview = SqlFragment(sql, "duckdb").transpileTo(target)
            assertTrue(preview.unsupportedMessages.isNotEmpty())
            assertTrue(preview.unsupportedMessages.none { secret in it })
        }
        val script = "$create; $register; SHOW ALL EXTERNAL RESOURCES; DESTROY EXTERNAL RESOURCE \"Case Name\"; SELECT 1;"
        val snapshot = SqlScriptSnapshot.parse(script, "duckdb")
        assertEquals(5, snapshot.statements.count { it.hasSql })
        assertTrue(snapshot.statements.all { it.boundaryError == null })
        assertEquals(5, Dialects.DUCKDB.parse(script).filterNotNull().filterNot { it is Semicolon }.size)
    }
}
