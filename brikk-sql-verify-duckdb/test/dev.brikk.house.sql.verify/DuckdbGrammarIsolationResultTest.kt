package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

internal class DuckdbGrammarIsolationResultTest {
    @Test fun loadedGrammarActivationDoesNotChangeTheStaticDialectOrBindEditorArguments() {
        val fixture = System.getenv("BRIKK_DUCKDB_PSQL_EXTENSION")
        assumeTrue(fixture != null, "Explicit matching signed local psql fixture required")
        val binary = Path.of(fixture!!)
        require(Files.isRegularFile(binary) && binary.fileName.toString() == "psql.duckdb_extension")
        val root = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "grammar-isolation-")
        try {
            val repository = RepositoryFixture(root)
            repository.connect().use { c ->
                fun scalar(sql: String) = c.createStatement().use { s ->
                    s.executeQuery(sql).use { r ->
                        assertTrue(r.next())
                        r.getString(1)
                    }
                }
                val pipeline = "FROM range(nextval('untouched')) |> EXTEND i + 1 AS n"
                val identifiers = "SELECT extend,rename,psql FROM t"
                val before = parseDuckdbNativeOne(identifiers)
                // Brikk's documented PIPE grammar can overlap PSQL but never depends on LOAD.
                val staticPipeline = SqlFragment(pipeline, "duckdb").toExecutable("duckdb")
                assertIs<SerializedSql.Failed>(c.serializeSql(pipeline))
                val original = Files.readAllBytes(binary)
                val payload = original.copyOf(original.size-256)
                val signer = Signature.getInstance("SHA256withRSA").apply { initSign(RepositoryTestKeys.first.private) }
                val hash = MessageDigest.getInstance("SHA-256")
        for (start in payload.indices step 1024 * 1024) signer.update(hash.digest(payload.copyOfRange(start, minOf(start + 1024 * 1024, payload.size))))
                val location = repository.prefix.resolve(scalar("SELECT version()")).resolve(scalar("PRAGMA platform"))
                Files.createDirectories(location)
        GZIPOutputStream(Files.newOutputStream(location.resolve("psql.duckdb_extension.gz"))).use { it.write(payload + signer.sign()) }
                c.createStatement().use { s ->
                    s.execute("CREATE SEQUENCE untouched")
                    s.execute(repository.create("grammar_fixture"))
                    s.execute("INSTALL psql FROM grammar_fixture")
                    s.execute("LOAD psql FROM grammar_fixture")
                }
                assertEquals("false", scalar("SELECT current_setting('allow_unsigned_extensions')"))
                val active = scalar("SELECT current_setting('active_grammar_extensions')")
                assertTrue("psql" in active.lowercase(), active)
                assertIs<SerializedSql.Parsed>(c.serializeSql(pipeline))
                assertIs<SerializedSql.Failed>(c.serializeSql("FROM range(1) |> EXTEND"))
                assertEquals(before, parseDuckdbNativeOne(identifiers))
                assertEquals(staticPipeline.sql, SqlFragment(pipeline, "duckdb").toExecutable("duckdb").sql)
                c.createStatement().use { it.execute("SET active_grammar_extensions=[]") }
                assertIs<SerializedSql.Failed>(c.serializeSql(pipeline))
                assertIs<SerializedSql.Parsed>(c.serializeSql(identifiers))
                assertEquals("1", scalar("SELECT nextval('untouched')"))
                assertEquals(before, parseDuckdbNativeOne(identifiers))
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
