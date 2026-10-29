package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import org.duckdb.DuckDBConnection
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

internal class DuckdbRoutingResultTest {
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { s ->
        s.queryTimeout = 5
        s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }

    @Test
    fun unsupportedLocalTargetsAndDisconnectHaveNativeErrorsWithoutChangingLocalState() {
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { it.execute("ATTACH ':memory:' AS attached") }
            val before = c.rows("SELECT database_name FROM duckdb_databases() ORDER BY database_name")
            for ((sql, reason) in listOf("CONNECT" to "not yet implemented", "CONNECT LOCAL" to "not yet implemented",
                "CONNECT missing" to "not attached", "CONNECT attached" to "does not support CONNECT",
                "CONNECT ':memory:'" to "does not support CONNECT", "DISCONNECT" to "no active CONNECT")) {
                val error = assertFailsWith<SQLException> { c.createStatement().use { it.execute(if (generated) Dialects.DUCKDB.parseOne(sql).sql("duckdb") else sql) } }
                assertTrue(reason in error.message.orEmpty(), reason)
                assertEquals(before, c.rows("SELECT database_name FROM duckdb_databases() ORDER BY database_name"))
            }
            assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM pragma_database_list WHERE name LIKE '__connect%'"))
        }
    }

    @Test
    fun backendStringsAndOptionsAreParseCheckedWithoutContactingAnyEndpoint() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (sql in listOf("CONNECT 'quack:never-contact.invalid' (TOKEN 'dummy',DISABLE_SSL true)",
                "CONNECT 'postgres:dbname=example host=never-contact.invalid password=dummy'",
                "CONNECT 'mysql:host=never-contact.invalid password=dummy'", "CONNECT 'quack:never-contact.invalid' (DISABLE_SSL true)")) {
                for (text in listOf(sql, Dialects.DUCKDB.parseOne(sql).sql("duckdb"))) {
                    val serialized = c.serializeSql(text)
                    assertEquals("not implemented", assertIs<SerializedSql.Failed>(serialized).type)
                }
            }
            assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM pragma_database_list WHERE name LIKE '__connect%'"))
        }
    }

    @Test
    fun nativeGrammarRejectsAliasOptionsNumbersAndExtraDisconnectTargets() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for (sql in listOf("CONNECT alias (TOKEN 'dummy')", "CONNECT LOCAL (TOKEN 'dummy')", "CONNECT 123",
                "CONNECT (SELECT 'x')", "CONNECT TO alias", "DISCONNECT alias", "CONNECT 'quack:host' (TOKEN = 'dummy')")) {
                assertEquals("parser", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type, "native routing syntax")
            }
        }
    }

    @Test
    fun analysisAndAllVerifierChannelsDoNotBindOptionsOrAcquireRoutingState() {
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { user ->
            user.createStatement().use { it.execute("CREATE SEQUENCE routing_probe START 1") }
            val sql = "CONNECT ':memory:' (TOKEN nextval('routing_probe'))"
            Dialects.DUCKDB.parseOne(sql).sql("duckdb")
            RelationScope.analyze(sql, "duckdb", RelationFacts())
            DuckdbVerifier({ user.duplicate() }).use { assertIs<VerifyResult.Unverified>(it.verify(sql)) }
            assertIs<VerifyResult.Unverified>(ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(user.duplicate()) }).verify(sql))
            ParseOnlyDuckdbVerifier().use { assertIs<VerifyResult.Unverified>(it.verify(sql)) }
            assertEquals(listOf(listOf("1")), user.rows("SELECT nextval('routing_probe')"))
            assertEquals(listOf(listOf("0")), user.rows("SELECT count(*) FROM pragma_database_list WHERE name LIKE '__connect%'"))
        }
    }

    @Test
    fun verifierDiagnosticsRedactRoutingValuesEvenWhenTheHelperThrowsOrReturnsThem() {
        val secret = "dummy-routing-credential"
        val sql = "CONNECT 'postgres:password=$secret'"
        val broken = ConnectionDuckdbVerifier({ throw SQLException("backend initialization echoed password=$secret") }).verify(sql)
        assertFalse(secret in assertIs<VerifyResult.Unavailable>(broken).reason)
        val document = """{"error":true,"error_type":"parser","error_message":"echoed $secret","location":"[8,1]"}"""
        val parsed = serializedSqlDocument(document)!!.toParseOnlyResult(sql)
        assertFalse(secret in parsed.toString())
    }
}
