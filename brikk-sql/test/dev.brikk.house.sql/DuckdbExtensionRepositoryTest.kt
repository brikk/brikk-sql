package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.mayContainDuckdbExtensionManagement
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbExtensionRepositoryTest {
    private val secret = "dummy-repository-credential"
    private val pem = "-----BEGIN PUBLIC KEY-----\n  Case+Sensitive/==\n-----END PUBLIC KEY-----"
    private val create = "CREATE EXTENSION REPOSITORY \"CaseRepo\" WITH PREFIX 'https://user:$secret@host/Case?token=$secret' USING PUBLIC KEYS '$pem', 'second-key'"

    @Test
    fun finalRepositoryAndInstallLoadFormsStayStructuredThroughCopyAndSerde() {
        for (sql in listOf(create, "CREATE OR REPLACE EXTENSION REPOSITORY repo PREFIX '/local/Case' PUBLIC KEY 'not validated by static parsing'",
            "CREATE EXTENSION REPOSITORY IF NOT EXISTS 'repo' WITH PREFIX 'https://never-contact.invalid'",
            "CREATE EXTENSION REPOSITORY repo PREFIX '/local' PUBLIC KEY 'one', 'two'", "DROP EXTENSION REPOSITORY repo",
            "DROP EXTENSION REPOSITORY IF EXISTS 'repo'", "INSTALL x", "INSTALL '/path/x.duckdb_extension'",
            "INSTALL x FROM community", "INSTALL x FROM \"CaseRepo\"", "INSTALL x FROM 'repo' VERSION '1.2.3'",
            "FORCE INSTALL AND LOAD x FROM repo VERSION '1.2.3'", "INSTALL AND LOAD x FROM 'repo'",
            "LOAD x", "LOAD '/path/x.duckdb_extension'", "LOAD x FROM \"CaseRepo\" AS \"Alias\"", "LOAD x FROM 'repo'")) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertTrue(tree.isDuckdbExtensionManagement(), sql)
            for (copy in listOf(tree.copy(), assertIs<Expression>(Serde.load(Serde.dump(tree))))) {
                assertEquals(tree, copy)
                val rendered = copy.sql("duckdb")
                assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
            }
            assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        }
        for (name in listOf("DuckdbCreateExtensionRepository", "DuckdbDropExtensionRepository", "DuckdbInstallExtension", "DuckdbLoadExtension"))
            assertEquals("brikk.duckdb", ExpressionRegistry.entries.getValue(name).module)
        // The existing manifest-backed simple INSTALL shape is deliberately unchanged.
        assertIs<Install>(Dialects.DUCKDB.parseOne("INSTALL httpfs FROM community"))
    }

    @Test
    fun literalKeyBytesAndNamedVersusStringRepositoriesAreNotNormalizedOrCollapsed() {
        val tree = assertIs<DuckdbCreateExtensionRepository>(Dialects.DUCKDB.parseOne(create))
        val normalized = normalizeIdentifiers(tree.copy(), dialect = Dialects.DUCKDB)
        assertEquals(pem, assertIs<Literal>(normalized.expressionsArg.first()).name)
        assertTrue(secret in assertIs<Literal>(normalized.args["prefix"]).name)
        assertTrue(pem in tree.sql("duckdb"))
        for (statement in listOf("LOAD x", "INSTALL AND LOAD x")) {
            val named = Dialects.DUCKDB.parseOne("$statement FROM \"CaseRepo\"")
            assertTrue(assertIs<Identifier>(named.args["from_"]).quoted)
            assertIs<Literal>(Dialects.DUCKDB.parseOne("$statement FROM 'CaseRepo'").args["from_"])
        }
        val fragment = SqlFragment(create, "duckdb")
        assertTrue(fragment.scalarParams.isEmpty())
        assertTrue(fragment.sourceTables.isEmpty())
        assertTrue(SqlScriptParameters.parse(create, "duckdb").occurrences.isEmpty())
    }

    @Test
    fun invalidGrammarDoesNotBecomeAnOpaqueCommandOrAnExpressionToEvaluate() {
        for (sql in listOf("CREATE EXTENSION REPOSITORY repo", "CREATE EXTENSION REPOSITORY repo WITH PREFIX ?",
            "CREATE EXTENSION REPOSITORY repo WITH PREFIX '/p' USING PUBLIC KEY ?",
            "CREATE EXTENSION REPOSITORY repo WITH PREFIX '/p' USING PUBLIC KEYS ('x','y')",
            "CREATE OR REPLACE EXTENSION REPOSITORY IF NOT EXISTS repo PREFIX '/p'", "DROP EXTENSION REPOSITORY IF NOT EXISTS repo",
            "LOAD 123", "LOAD ?", "LOAD (SELECT 'x')", "LOAD x VERSION 'v1'", "LOAD x AS 'alias'",
            "INSTALL AND x", "INSTALL x FROM ?", "INSTALL x VERSION ?", "INSTALL AND LOAD x AS alias")) {
            assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
        }
    }

    @Test
    fun parserEditorAndTokenizerDiagnosticsDoNotEchoRepositorySecretsOrKeyMaterial() {
        for (sql in listOf("$create trailing extra", "LOAD 'https://u:$secret@host", "CREATE EXTENSION REPOSITORY x PREFIX 'https://$secret' PUBLIC KEY '$secret',")) {
            val error = runCatching { Dialects.DUCKDB.parseOne(sql) }.exceptionOrNull() ?: fail("Expected invalid extension statement")
            assertTrue(error is ParseError || error is TokenError)
            assertFalse(secret in error.toString())
            assertNull(error.cause)
            if (error is ParseError) for (info in error.errors) assertFalse(listOf(info.description, info.startContext, info.highlight, info.endContext).any { it?.contains(secret) == true })
            assertFalse(SqlScriptSnapshot.parse(sql, "duckdb").statements.any { it.boundaryError?.contains(secret) == true })
        }
        assertTrue(mayContainDuckdbExtensionManagement("LOAD x"))
        assertTrue(mayContainDuckdbExtensionManagement("CREATE OR REPLACE EXTENSION REPOSITORY x PREFIX '/p'"))
        assertFalse(mayContainDuckdbExtensionManagement("SELECT 'INSTALL',\"LOAD\" -- EXTENSION REPOSITORY\n"))
    }

    @Test
    fun createHasANativeManagementResultButNoTableScopeOrInventedLineage() {
        val facts = RelationFacts(catalog = CatalogKnowledge.CLOSED)
        val scope = RelationScope.analyze(create, "duckdb", facts)
        assertTrue(scope.parsed)
        assertTrue(scope.references.isEmpty())
        assertTrue(scope.diagnostics.isEmpty())
        val output = assertNotNull(scope.output)
        assertEquals(listOf("repository_name", "prefix", "key_fingerprints"), output.columns.map { it.name })
        assertEquals(listOf("VARCHAR", "VARCHAR", "VARCHAR[]"), output.columns.map { it.sqlType })
        assertTrue(output.columns.all { it.origin == ColumnOrigin.COMPUTED && it.provenance == null })
        assertEquals(output.columns.map { it.sqlType }, SqlFragment(create, "duckdb").outputShape().columns.map { it.type })
        for (sql in listOf("DROP EXTENSION REPOSITORY repo", "INSTALL x", "INSTALL AND LOAD x FROM repo", "LOAD x FROM repo")) {
            assertNull(RelationScope.analyze(sql, "duckdb", facts).output)
            assertFailsWith<ShapeError> { SqlFragment(sql, "duckdb").outputShape() }
        }
        for (sql in listOf(create, "LOAD x", "INSTALL x")) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertFailsWith<OptimizeError> { qualify(tree.copy(), dialect = Dialects.DUCKDB) }
            if (tree is Install) assertIs<Install>(annotateTypes(tree.copy(), dialect = Dialects.DUCKDB))
            else assertFailsWith<OptimizeError> { annotateTypes(tree.copy(), dialect = Dialects.DUCKDB) }
            assertFailsWith<SqlglotError> { lineageAll(sql, dialect = Dialects.DUCKDB) }
        }
    }

    @Test
    fun executionRefusesUnprovedCrossTargetManagementAndScriptsKeepTheirBoundaries() {
        for (sql in listOf(create, "DROP EXTENSION REPOSITORY repo", "INSTALL x FROM repo", "FORCE INSTALL AND LOAD x FROM repo VERSION 'v1'", "LOAD x FROM repo AS alias")) {
            for (target in listOf("postgres", "trino", "doris", "clickhouse", "mysql")) {
                assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable(target) }
                val preview = SqlFragment(sql, "duckdb").transpileTo(target)
                assertTrue(preview.unsupportedMessages.isNotEmpty())
                assertTrue(preview.unsupportedMessages.none { secret in it })
            }
        }
        val script = "$create; INSTALL AND LOAD x FROM \"CaseRepo\"; DROP EXTENSION REPOSITORY \"CaseRepo\"; SELECT 1;"
        val ranges = SqlScriptSnapshot.parse(script, "duckdb").statements
        assertEquals(4, ranges.count { it.hasSql })
        assertTrue(ranges.all { it.boundaryError == null })
    }
}
