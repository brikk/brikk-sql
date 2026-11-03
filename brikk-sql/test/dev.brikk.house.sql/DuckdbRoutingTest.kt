package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.mayContainDuckdbRouting
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.SqlglotError
import dev.brikk.house.sql.optimizer.lineageAll
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbRoutingTest {
    private val secret = "dummy-route-credential"

    @Test
    fun finalTargetFormsAndBackendStringsRemainStructuredWithoutConnecting() {
        for (sql in listOf("CONNECT", "CONNECT LOCAL", "CONNECT remote_db", "CONNECT \"LOCAL\"", "CONNECT \"db.with.dot\"", "DISCONNECT",
            "CONNECT 'quack:server.example' (TOKEN '$secret', DISABLE_SSL true)",
            "CONNECT 'postgres:dbname=example host=127.0.0.1 password=$secret'",
            "CONNECT 'mysql:host=127.0.0.1 user=test password=$secret'",
            "CONNECT 'quack:server.example' (TOKEN '$secret', READ_ONLY, timeout (1+2))")) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertTrue(tree is SessionConnect || tree is SessionDisconnect)
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val rendered = tree.sql("duckdb")
            assertEquals(rendered, Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
            if (secret in sql) assertTrue(secret in rendered)
            assertTrue(SqlFragment(sql, "duckdb").scalarParams.isEmpty())
            assertTrue(SqlFragment(sql, "duckdb").sourceTables.isEmpty())
        }
        val local = assertIs<SessionConnect>(Dialects.DUCKDB.parseOne("CONNECT LOCAL"))
        assertIs<Var>(local.thisArg)
        val quoted = assertIs<SessionConnect>(Dialects.DUCKDB.parseOne("CONNECT \"LOCAL\""))
        assertTrue(assertIs<Identifier>(quoted.thisArg).quoted)
        assertIs<Literal>(Dialects.DUCKDB.parseOne("'DISCONNECT'"))
        assertIs<Column>(Dialects.DUCKDB.parseOne("\"CONNECT\""))
        for (sql in listOf("CONNECT", "CONNECT LOCAL")) assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
        assertTrue(SqlFragment("CONNECT \"LOCAL\"", "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
    }

    @Test
    fun invalidRoutingSyntaxDoesNotBecomeAnotherValidStatement() {
        for (sql in listOf("CONNECT 123", "CONNECT (SELECT 'x')", "CONNECT attached (TOKEN '$secret')",
            "CONNECT LOCAL (TOKEN '$secret')", "CONNECT TO attached", "DISCONNECT attached", "DISCONNECT '$secret'",
            "CONNECT 'quack:host' (TOKEN = '$secret')")) {
            val error = assertFailsWith<ParseError> { Dialects.DUCKDB.parseOne(sql) }
            assertTrue("redacted" in error.message.orEmpty())
            assertFalse(secret in error.toString())
        }
    }

    @Test
    fun routingDiagnosticsNeverEchoOptionValuesOrUriCredentials() {
        for (sql in listOf("CONNECT 'postgres:user=test password=$secret' (TOKEN '$secret',",
            "CONNECT 'quack:host' (TOKEN '$secret' bogus extra)", "CONNECT 'quack:$secret")) {
            val error = runCatching { Dialects.DUCKDB.parseOne(sql) }.exceptionOrNull() ?: fail("Expected invalid routing input")
            assertTrue(error is ParseError || error is TokenError)
            assertFalse(secret in error.toString())
            assertNull(error.cause)
            if (error is ParseError) for (info in error.errors) {
                assertFalse(listOf(info.description, info.startContext, info.highlight, info.endContext).any { it?.contains(secret) == true })
            }
        }
        val broken = "CONNECT 'quack:$secret"
        assertFalse(SqlScriptSnapshot.parse(broken, "duckdb").statements.any { it.boundaryError?.contains(secret) == true })
        assertFalse(SqlScriptParameters.parse(broken, "duckdb").error?.contains(secret) == true)
        assertFalse(mayContainDuckdbRouting("SELECT 'CONNECT',\"DISCONNECT\""))
    }

    @Test
    fun routingIsNotAQueryOutputScopeOptimizerInputOrPortableStatement() {
        for (sql in listOf("CONNECT 'quack:host' (TOKEN '$secret')", "CONNECT attached", "DISCONNECT")) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            val scope = RelationScope.analyze(sql, "duckdb", RelationFacts(catalog = CatalogKnowledge.CLOSED))
            assertTrue(scope.diagnostics.isEmpty())
            assertNull(scope.output)
            assertFailsWith<ShapeError> { SqlFragment(sql, "duckdb").outputShape() }
            assertFailsWith<OptimizeError> { qualify(tree, dialect = Dialects.DUCKDB) }
            assertFailsWith<SqlglotError> { lineageAll(sql, dialect = Dialects.DUCKDB) }
            for (target in listOf("postgres", "trino", "doris", "clickhouse")) {
                val refusal = assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable(target) }
                assertFalse(secret in refusal.toString())
                val preview = SqlFragment(sql, "duckdb").transpileTo(target)
                assertTrue(preview.unsupportedMessages.isNotEmpty())
                assertTrue(preview.unsupportedMessages.none { secret in it })
            }
        }
    }
}
