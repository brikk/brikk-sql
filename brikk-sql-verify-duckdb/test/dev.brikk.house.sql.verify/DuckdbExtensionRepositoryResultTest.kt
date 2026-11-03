package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.execute
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.rows
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.scalar
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.duckdb.DuckDBConnection
import kotlin.test.*

internal object RepositoryTestKeys {
    val first: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    val second: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    fun compact(key: KeyPair) = Base64.getEncoder().encodeToString(key.public.encoded)
    fun pem(key: KeyPair) = "-----BEGIN PUBLIC KEY-----\n" + compact(key).chunked(64).joinToString("\n") + "\n-----END PUBLIC KEY-----"
    fun fingerprint(key: KeyPair) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(key.public.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal class RepositoryFixture(val directory: Path) {
    val repositories: Path = directory.resolve("trust")
    val extensions: Path = directory.resolve("installed")
    val prefix: Path = directory.resolve("served")
    fun connect(policy: String = "allowed"): DuckDBConnection = DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
        setProperty("extension_repository_directory", repositories.toString())
        setProperty("extension_directory", extensions.toString())
        setProperty("allow_extension_repositories", policy)
        setProperty("autoinstall_known_extensions", "false")
        setProperty("autoload_known_extensions", "false")
    }) as DuckDBConnection
    fun create(name: String = "private_repo", key: String? = RepositoryTestKeys.compact(RepositoryTestKeys.first)): String =
        "CREATE EXTENSION REPOSITORY $name WITH PREFIX ${quote(prefix.toString())}" + (key?.let { " USING PUBLIC KEY ${quote(it)}" } ?: "")
    fun metadata(vararg keys: KeyPair) {
        Files.createDirectories(prefix.resolve(".well-known"))
        Files.writeString(prefix.resolve(".well-known/duckdb-extension-repo.json"), buildJsonObject {
            put("signature_keys", buildJsonArray { keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(RepositoryTestKeys.pem(it))) } })
        }.toString())
    }
    fun trustFiles(): List<Path> = if (!Files.exists(repositories)) emptyList() else Files.list(repositories).use { it.toList() }
    companion object {
        fun quote(value: String) = "'${value.replace("'", "''")}'"
        fun render(sql: String, generated: Boolean): String = if (generated)
            assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql).copy()))).sql("duckdb") else sql
        fun Connection.execute(sql: String, generated: Boolean = false) = createStatement().use { s ->
            s.queryTimeout = 5
            if (s.execute(render(sql, generated))) s.resultSet.use { while (it.next()) { /* consume once, never retry */ } }
        }
        fun Connection.rows(sql: String, generated: Boolean = false): List<List<String?>> = createStatement().use { s ->
            s.queryTimeout = 5
            s.executeQuery(render(sql, generated)).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
        }
        fun Connection.scalar(sql: String): String = rows(sql).single().single()!!
        fun scratch(block: (RepositoryFixture) -> Unit) {
            val parent = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode").toAbsolutePath())
            val directory = Files.createTempDirectory(parent, "duckdb-repositories-")
            try { block(RepositoryFixture(directory)) } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }
}

internal class DuckdbExtensionRepositoryResultTest {
    private fun modes(block: (RepositoryFixture, DuckDBConnection, Boolean) -> Unit) {
        for (generated in listOf(false, true)) RepositoryFixture.scratch { f -> f.connect().use { block(f, it, generated) } }
    }
    private fun Connection.listResult(sql: String, column: Int = 1, generated: Boolean = false): List<String> = createStatement().use { statement ->
        statement.executeQuery(RepositoryFixture.render(sql, generated)).use { rows ->
            assertTrue(rows.next())
            (rows.getArray(column).array as Array<*>).map { it.toString() }
        }
    }

    @Test
    fun createReportsPinnedFingerprintsAndMatchesTheStaticManagementResultContract() = modes { f, c, generated ->
        val sql = f.create()
        val grammars = c.scalar("SELECT current_setting('active_grammar_extensions')")
        c.createStatement().use { statement -> statement.executeQuery(RepositoryFixture.render(sql, generated)).use { r ->
            val shape = SqlFragment(sql, "duckdb").outputShape()
            assertEquals(shape.columns.map { it.name }, (1..r.metaData.columnCount).map { r.metaData.getColumnName(it) })
            assertEquals(shape.columns.map { it.type }, (1..r.metaData.columnCount).map { r.metaData.getColumnTypeName(it) })
            assertTrue(r.next())
            assertEquals("private_repo", r.getString(1))
            assertEquals(f.prefix.toString(), r.getString(2))
            assertEquals(listOf(RepositoryTestKeys.fingerprint(RepositoryTestKeys.first)), (r.getArray(3).array as Array<*>).map { it.toString() })
            assertFalse(r.next())
        } }
        assertEquals(1, f.trustFiles().size)
        assertEquals(grammars, c.scalar("SELECT current_setting('active_grammar_extensions')"))
        assertEquals("false", c.scalar("SELECT current_setting('allow_unsigned_extensions')"))
        val before = c.rows("SELECT prefix,public_keys FROM duckdb_extension_repositories() WHERE repository_name='private_repo'")
        assertFailsWith<SQLException> { c.execute(sql, generated) }
        c.execute(sql.replace("REPOSITORY private_repo", "REPOSITORY IF NOT EXISTS private_repo").replace(f.prefix.toString(), "/never-read"), generated)
        assertEquals(before, c.rows("SELECT prefix,public_keys FROM duckdb_extension_repositories() WHERE repository_name='private_repo'"))
        val replacement = f.create(key = RepositoryTestKeys.pem(RepositoryTestKeys.second)).replace("CREATE EXTENSION", "CREATE OR REPLACE EXTENSION")
        c.execute(replacement, generated)
        assertEquals(listOf(RepositoryTestKeys.compact(RepositoryTestKeys.second)), c.listResult("SELECT public_keys FROM duckdb_extension_repositories() WHERE repository_name='private_repo'"))
        c.execute("DROP EXTENSION REPOSITORY private_repo", generated)
        c.execute("DROP EXTENSION REPOSITORY IF EXISTS private_repo", generated)
        assertTrue(f.trustFiles().isEmpty())
    }

    @Test
    fun multipleKeysAndMetadataFetchPinOnceAndSurviveAnotherDatabaseInstance() = modes { f, c, generated ->
        val multiple = f.create().replace("PUBLIC KEY", "PUBLIC KEYS") + ", " + RepositoryFixture.quote(RepositoryTestKeys.pem(RepositoryTestKeys.second))
        val result = c.listResult(multiple, column = 3, generated = generated)
        assertEquals(listOf(RepositoryTestKeys.fingerprint(RepositoryTestKeys.first), RepositoryTestKeys.fingerprint(RepositoryTestKeys.second)), result)
        c.execute("DROP EXTENSION REPOSITORY private_repo", generated)
        f.metadata(RepositoryTestKeys.first)
        c.execute(f.create(key = null), generated)
        f.metadata(RepositoryTestKeys.second)
        f.connect("undecided").use { reopened ->
            assertEquals(listOf(RepositoryTestKeys.compact(RepositoryTestKeys.first)), reopened.listResult("SELECT public_keys FROM duckdb_extension_repositories() WHERE repository_name='private_repo'"))
            assertEquals("undecided", reopened.scalar("SELECT current_setting('allow_extension_repositories')"))
        }
        c.execute(f.create(key = null).replace("CREATE EXTENSION", "CREATE OR REPLACE EXTENSION"), generated)
        assertEquals(listOf(RepositoryTestKeys.compact(RepositoryTestKeys.second)), c.listResult("SELECT public_keys FROM duckdb_extension_repositories() WHERE repository_name='private_repo'"))
    }

    @Test
    fun invalidKeysMetadataAndNamesFailWithoutInstallingOrWeakeningSignatureChecks() = modes { f, c, generated ->
        for (sql in listOf(f.create(key = "not a key"), f.create(name = "core"), f.create(name = "'../escape'"),
            f.create().replace("PUBLIC KEY", "PUBLIC KEYS") + ", 'bad key'")) {
            assertFailsWith<SQLException> { c.execute(sql, generated) }
            assertTrue(f.trustFiles().isEmpty())
        }
        f.metadata(RepositoryTestKeys.first)
        Files.writeString(f.prefix.resolve(".well-known/duckdb-extension-repo.json"), "not json")
        assertFailsWith<SQLException> { c.execute(f.create(key = null), generated) }
        Files.writeString(f.prefix.resolve(".well-known/duckdb-extension-repo.json"), "{\"other\":1}")
        assertFailsWith<SQLException> { c.execute(f.create(key = null), generated) }
        assertTrue(f.trustFiles().isEmpty())
        assertFalse(Files.exists(f.extensions))
        assertEquals("false", c.scalar("SELECT current_setting('allow_unsigned_extensions')"))
    }

    @Test
    fun runtimePolicyTighteningAndConfigurationLockRemainNativeExecutionDecisions() = RepositoryFixture.scratch { f ->
        f.connect("undecided").use { c ->
            assertFailsWith<SQLException> { c.execute(f.create()) }
            c.execute("SET allow_extension_repositories='allowed'")
            c.execute(f.create(), true)
            assertFailsWith<SQLException> { c.execute("SET allow_extension_repositories='undecided'") }
            c.execute("SET allow_extension_repositories='forbidden'")
            assertFailsWith<SQLException> { c.execute(f.create("blocked"), true) }
            assertFailsWith<SQLException> { c.execute("INSTALL missing FROM private_repo", true) }
            assertEquals("1", c.scalar("SELECT count(*) FROM duckdb_extension_repositories() WHERE type='USER_PROVIDED'"))
            c.execute("DROP EXTENSION REPOSITORY private_repo", true)
            assertFailsWith<SQLException> { c.execute("SET allow_extension_repositories='allowed'") }
        }
        f.connect().use { c ->
            c.execute(f.create())
            assertFailsWith<SQLException> { c.execute("SET extension_repository_directory='/not-the-test-trust-store'") }
            c.execute("SET lock_configuration=true")
            assertFailsWith<SQLException> { c.execute(f.create("blocked")) }
            assertFailsWith<SQLException> { c.execute("DROP EXTENSION REPOSITORY private_repo") }
        }
    }

    @Test
    fun allAnalysisAndVerifierChannelsStayParseOnlyAndNeverPrepareFetchOrWriteTrust() = RepositoryFixture.scratch { f ->
        f.connect().use { user ->
            f.metadata(RepositoryTestKeys.first)
            val prepares = AtomicInteger()
            val statements = listOf(f.create(key = null), f.create(), "DROP EXTENSION REPOSITORY private_repo",
                "INSTALL missing FROM 'https://never-contact.invalid'", "FORCE INSTALL AND LOAD missing FROM private_repo VERSION 'v1'", "LOAD missing FROM private_repo")
            for (sql in statements) {
                Dialects.DUCKDB.parseOne(sql).sql("duckdb")
                RelationScope.analyze(sql, "duckdb", RelationFacts())
                // One owned helper, not a new duplicate for each method call.
                val helper = user.duplicate()
                val guarded = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement") { prepares.incrementAndGet(); throw AssertionError("Management validation must never prepare") }
                    try { method.invoke(helper, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
                } as Connection
                DuckdbVerifier({ guarded }).use { assertIs<VerifyResult.Unverified>(it.verify(sql)) }
                assertIs<VerifyResult.Unverified>(ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(user.duplicate()) }).verify(sql))
                ParseOnlyDuckdbVerifier().use { assertIs<VerifyResult.Unverified>(it.verify(sql)) }
            }
            assertEquals(0, prepares.get())
            assertTrue(f.trustFiles().isEmpty())
            assertFalse(Files.exists(f.extensions))
            assertEquals("0", user.scalar("SELECT count(*) FROM duckdb_extension_repositories() WHERE type='USER_PROVIDED'"))
        }
    }

    @Test
    fun nativeGrammarAndRedactedVerifierErrorsKeepLiteralOnlyRepositoriesAndKeys() = RepositoryFixture.scratch { f ->
        f.connect().use { c ->
            for (sql in listOf("LOAD x FROM repo AS \"Alias\"", "LOAD 'x' FROM 'repo'",
                "INSTALL x FROM repo VERSION '1.2.3'", "FORCE INSTALL AND LOAD x FROM repo VERSION '1.2.3'")) {
                assertEquals("not implemented", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
                assertEquals("not implemented", assertIs<SerializedSql.Failed>(c.serializeSql(RepositoryFixture.render(sql, true))).type)
            }
            for (sql in listOf("CREATE EXTENSION REPOSITORY x PREFIX ?", "CREATE EXTENSION REPOSITORY x PREFIX '/p' PUBLIC KEY ?",
                "CREATE OR REPLACE EXTENSION REPOSITORY IF NOT EXISTS x PREFIX '/p'", "LOAD x VERSION 'v1'", "LOAD x AS 'alias'")) {
                assertEquals("parser", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
            }
            val secret = "dummy-private-repository-token"
            val sql = "CREATE EXTENSION REPOSITORY x PREFIX 'https://u:$secret@host' PUBLIC KEY ?"
            ParseOnlyDuckdbVerifier().use { assertFalse(secret in it.verify(sql).toString()) }
            val broken = ConnectionDuckdbVerifier({ throw SQLException("helper echoed $secret") }).verify(sql)
            assertFalse(secret in broken.toString())
        }
    }
}
