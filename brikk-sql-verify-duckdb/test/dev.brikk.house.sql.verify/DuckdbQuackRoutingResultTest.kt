package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.*

/** Explicit opt-in dependency, never INSTALL/autoload into a user's extension store. */
internal class DuckdbQuackRoutingResultTest {
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { s ->
        s.queryTimeout = 5
        s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun Connection.command(sql: String, generated: Boolean = false) {
        try { createStatement().use { s -> s.queryTimeout = 5; s.execute(if (generated) Dialects.DUCKDB.parseOne(sql).sql("duckdb") else sql) } }
        catch (_: SQLException) { throw AssertionError("Loopback routing fixture statement failed; credentials redacted") }
    }

    @Test
    fun namedAndEphemeralLoopbackRoutesKeepSessionScopeAndCleanupState() {
        val extension = System.getenv("BRIKK_DUCKDB_QUACK_EXTENSION")
        assumeTrue(extension != null, "Set BRIKK_DUCKDB_QUACK_EXTENSION to the signed v2.0.0 quack.duckdb_extension to run loopback routing")
        require(Files.isRegularFile(Path.of(extension!!)))
        for (generated in listOf(false, true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.command("SET autoinstall_known_extensions=false")
            c.command("SET autoload_known_extensions=false")
            assertEquals(listOf(listOf("false")), c.rows("SELECT current_setting('allow_unsigned_extensions')"))
            c.command("LOAD httpfs")
            c.command("LOAD '${extension.replace("'", "''")}'")
            val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
            val uri = "quack:127.0.0.1:$port"
            val token = UUID.randomUUID().toString()
            c.command("CALL quack_serve('$uri',disable_ssl:=true,token:='$token',create_secret_if_not_exists:=false)")
            try {
                c.command("SET VARIABLE routing_mark='local'")
                c.command("ATTACH ':memory:' AS private_local")
                c.command("CREATE TABLE private_local.probe(i INT)")
                c.command("INSERT INTO private_local.probe VALUES(1)")
                c.command("ATTACH '$uri' AS qk (TOKEN '$token',DISABLE_SSL true)")
                c.command("CONNECT qk", generated)
                c.command("SET VARIABLE routing_mark='remote'")
                assertEquals(listOf(listOf("remote")), c.rows("SELECT getvariable('routing_mark') FROM private_local.probe"))
                // A failed forwarded CONNECT does not silently return us to local scope.
                val failed = runCatching { c.createStatement().use { it.execute("CONNECT nonexistent_remote_alias") } }.isFailure
                assertTrue(failed)
                assertEquals(listOf(listOf("remote")), c.rows("SELECT getvariable('routing_mark')"))
                c.command("DISCONNECT", generated)
                assertEquals(listOf(listOf("local")), c.rows("SELECT getvariable('routing_mark')"))
                val badAuthentication = runCatching { c.createStatement().use {
                    it.execute("CONNECT '$uri' (TOKEN 'wrong-fixture-token',DISABLE_SSL true)")
                } }.isFailure
                assertTrue(badAuthentication)
                assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM pragma_database_list WHERE name LIKE '__connect%'"))
                assertEquals(listOf(listOf("local")), c.rows("SELECT getvariable('routing_mark')"))
                c.command("CONNECT qk", generated)
                assertEquals(listOf(listOf("remote")), c.rows("SELECT getvariable('routing_mark')"))
                c.command("DISCONNECT", generated)
                c.command("DETACH qk")
                val direct = "CONNECT '$uri' (TOKEN '$token',DISABLE_SSL true)"
                c.command(direct, generated)
                c.command("SET VARIABLE ephemeral_only=42")
                c.command("DISCONNECT", generated)
                assertEquals(listOf(listOf("0")), c.rows("SELECT count(*) FROM pragma_database_list WHERE name LIKE '__connect%'"))
                c.command(direct, generated)
                assertEquals(listOf(listOf(null)), c.rows("SELECT getvariable('ephemeral_only')"))
                c.command("DISCONNECT", generated)
                assertEquals(listOf(listOf("local")), c.rows("SELECT getvariable('routing_mark')"))
            } finally {
                // Fixture-only resources. Never a user's source connection or backend.
                runCatching { c.createStatement().use { it.execute("DISCONNECT") } }
                c.command("CALL quack_stop('$uri')")
            }
        }
    }
}
