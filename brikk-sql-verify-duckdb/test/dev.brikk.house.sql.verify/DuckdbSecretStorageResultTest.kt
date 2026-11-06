package dev.brikk.house.sql.verify

import org.duckdb.DuckDBConnection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

/** Only synthetic HTTP config secrets in a private directory. No provider is contacted. */
internal class DuckdbSecretStorageResultTest {
    private val token = "synthetic-test-only"
    private fun connect(root: Path, persistent: Boolean = false) = DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
        setProperty("secret_directory", root.resolve("secrets").toString())
        setProperty("extension_directory", root.resolve("extensions").toString())
        setProperty("autoload_known_extensions", "false")
        setProperty("autoinstall_known_extensions", "false")
        setProperty("allow_persistent_secrets", persistent.toString())
        setProperty("allow_unredacted_secrets", "false")
    }) as DuckDBConnection
    private fun Connection.executeSecret(sql: String) {
        try { createStatement().use { it.execute(sql) } } catch (e: SQLException) {
            // Neither the exception's SQL snippet nor its cause may reach the test log.
            throw AssertionError("Secret control failed; SQL state ${e.sqlState}")
        }
    }
    private fun Connection.names(): Set<String> = createStatement().use { s -> s.executeQuery("SELECT name FROM duckdb_secrets()").use { r -> buildSet { while (r.next()) add(r.getString(1)) } } }
    private fun Connection.create(name: String, storage: String = "", persistence: String = "") = executeSecret(
        "CREATE $persistence SECRET $name $storage (TYPE http,BEARER_TOKEN '$token',SCOPE 'https://audit.invalid')")
    private fun scratch(block: (Path) -> Unit) {
        val root = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "secret-lifetime-")
        try { block(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun connectionStorageIsPrivateToTheConnectionAndTransactionsControlCreationLifetime() = scratch { root ->
        connect(root).use { owner -> owner.duplicate().use { sibling ->
            owner.create("shared_memory")
            owner.create("private_connection", "IN connection")
            assertEquals(setOf("shared_memory", "private_connection"), owner.names())
            assertEquals(setOf("shared_memory"), sibling.names())
            owner.duplicate().use { lateSibling -> assertEquals(setOf("shared_memory"), lateSibling.names()) }
            owner.executeSecret("BEGIN")
            owner.create("rollback_memory")
                owner.create("rollback_connection", "IN connection")
            owner.executeSecret("ROLLBACK")
            assertEquals(setOf("shared_memory", "private_connection"), owner.names())
            owner.executeSecret("BEGIN")
                owner.create("committed_connection", "IN connection")
                owner.executeSecret("COMMIT")
            assertTrue("committed_connection" in owner.names())
            owner.createStatement().use { s -> s.executeQuery("SELECT secret_string FROM duckdb_secrets()").use { r ->
                while (r.next()) assertTrue(token !in r.getString(1), "Native secrets were not redacted")
            } }
            val sql = "CREATE SECRET never_created IN connection (TYPE http,BEARER_TOKEN '$token')"
            val reply = owner.serializeSql(sql)
            assertTrue(reply is SerializedSql.Failed && reply.type == "not implemented", "Secret syntax must stay unverified without execution")
            assertTrue(token !in reply.toString(), "Parse-only diagnostic disclosed a secret")
            val transaction = owner.serializeSql("CREATE TRANSACTION SECRET never_created IN connection (TYPE http)")
            assertTrue(transaction is SerializedSql.Failed && transaction.type == "parser", "Internal TRANSACTION persist type is not exposed by this SQL grammar")
            assertTrue("never_created" !in owner.names())
            owner.close()
            assertEquals(setOf("shared_memory"), sibling.names())
        } }
        assertTrue(Files.list(root).use { it.noneMatch { path -> path.fileName.toString() == "secrets" && Files.list(path).use { files -> files.findAny().isPresent } } }, "Temporary secrets must not persist")
    }
    @Test fun persistentSecretFilesArePrivateFixturesAndCanBeReadBackAfterInstanceClosure() = scratch { root ->
        connect(root, true).use { c ->
            c.create("persistent_fixture", persistence = "PERSISTENT")
            c.createStatement().use { s ->
                s.executeQuery("SELECT persistent FROM duckdb_secrets() WHERE name='persistent_fixture'").use { r ->
                    assertTrue(r.next())
                    assertTrue(r.getBoolean(1))
                }
            }
        }
        assertTrue(Files.exists(root.resolve("secrets")))
        connect(root, true).use { c ->
            assertEquals(setOf("persistent_fixture"), c.names())
            c.executeSecret("DROP PERSISTENT SECRET persistent_fixture")
            assertTrue(c.names().isEmpty())
        }
    }
}
