package dev.brikk.house.sql.verify

import dev.brikk.house.sql.verify.RepositoryFixture.Companion.execute
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.quote
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.rows
import dev.brikk.house.sql.verify.RepositoryFixture.Companion.scalar
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Signature
import java.sql.Connection
import java.sql.SQLException
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** Opt-in native binary. Re-sign only a private scratch copy, never the user's extension store. */
internal class DuckdbSignedRepositoryResultTest {
    private fun source(): Path {
        val value = System.getenv("BRIKK_DUCKDB_REPOSITORY_EXTENSION")
        assumeTrue(value != null, "Set BRIKK_DUCKDB_REPOSITORY_EXTENSION to a trusted matching inet.duckdb_extension for signed repository execution controls")
        return Path.of(value!!).also { require(Files.isRegularFile(it) && it.fileName.toString() == "inet.duckdb_extension") }
    }

    private fun publish(f: RepositoryFixture, c: Connection, source: Path): Path {
        val original = Files.readAllBytes(source)
        require(original.size > 512)
        val payload = original.copyOf(original.size - 256)
        // DuckDB signs SHA256(concat(SHA256(each 1 MiB chunk))), using RSA PKCS#1 v1.5.
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(RepositoryTestKeys.first.private) }
        val digest = MessageDigest.getInstance("SHA-256")
        var start = 0
        while (start < payload.size) {
            val end = minOf(start + 1024 * 1024, payload.size)
            signer.update(digest.digest(payload.copyOfRange(start, end)))
            start = end
        }
        val signed = payload + signer.sign()
        val binary = f.directory.resolve("inet.duckdb_extension")
        Files.write(binary, signed)
        val location = f.prefix.resolve(c.scalar("SELECT version()")).resolve(c.scalar("PRAGMA platform"))
        Files.createDirectories(location)
        GZIPOutputStream(Files.newOutputStream(location.resolve("inet.duckdb_extension.gz"))).use { it.write(signed) }
        return binary
    }

    private fun installedCopies(f: RepositoryFixture): List<Path> = if (!Files.exists(f.extensions)) emptyList() else Files.walk(f.extensions).use {
        it.filter { path -> path.fileName.toString() == "inet.duckdb_extension" }.toList()
    }

    @Test
    fun namedRepositoryInstallationAndLoadUsePrivateStorageAndExistingTrustAcrossSessions() {
        val source = source()
        for (generated in listOf(false, true)) RepositoryFixture.scratch { f ->
            f.connect().use { c ->
                publish(f, c, source)
                c.execute(f.create(), generated)
                c.execute("INSTALL inet FROM private_repo", generated)
                val installed = installedCopies(f).single()
                assertTrue(installed.toString().contains("repositories/private_repo/"))
                assertEquals("false", c.scalar("SELECT loaded FROM duckdb_extensions() WHERE extension_name='inet'"))
                assertFailsWith<SQLException> { c.execute("LOAD inet", generated) }
                c.execute("LOAD inet FROM private_repo", generated)
                assertEquals("127.0.0.1", c.scalar("SELECT host('127.0.0.1'::INET)"))
                assertEquals(listOf(listOf("false", null)), c.rows("SELECT installed,signature_key_fingerprint FROM duckdb_extensions() WHERE extension_name='inet'"))
                assertEquals("false", c.scalar("SELECT current_setting('allow_unsigned_extensions')"))
            }
            // 'undecided' retains trust of already-installed custom extensions, but blocks installs.
            f.connect("undecided").use { c ->
                assertFailsWith<SQLException> { c.execute("INSTALL inet FROM private_repo", generated) }
                // LOAD accepts the string form; the combined statement's follow-up
                // load is the form that loses this selection.
                c.execute("LOAD inet FROM 'private_repo'", generated)
                assertEquals("127.0.0.1", c.scalar("SELECT host('127.0.0.1'::INET)"))
            }
        }
    }

    @Test
    fun combinedInstallAndLoadPreservesTheNativeIdentifierVersusStringDifferenceAndPartialInstall() {
        val source = source()
        for (generated in listOf(false, true)) {
            RepositoryFixture.scratch { f -> f.connect().use { c ->
                publish(f, c, source)
                c.execute(f.create(), generated)
                val error = assertFailsWith<SQLException> { c.execute("INSTALL AND LOAD inet FROM 'private_repo'", generated) }
                assertTrue("not found" in error.message.orEmpty())
                assertEquals(1, installedCopies(f).size, "The failed load does not roll back the successful install")
                assertEquals("false", c.scalar("SELECT loaded FROM duckdb_extensions() WHERE extension_name='inet'"))
                // Explicit user action after the error, not an application-side retry/workaround.
                c.execute("LOAD inet FROM private_repo", generated)
                assertEquals("127.0.0.1", c.scalar("SELECT host('127.0.0.1'::INET)"))
            } }
            RepositoryFixture.scratch { f -> f.connect().use { c ->
                publish(f, c, source)
                c.execute(f.create(), generated)
                c.execute("FORCE INSTALL AND LOAD inet FROM private_repo", generated)
                assertEquals("127.0.0.1", c.scalar("SELECT host('127.0.0.1'::INET)"))
            } }
        }
    }

    @Test
    fun repositoryKeysAreNotTrustedForOtherOriginsAndRotationOrForbiddingAffectsTheNextLoad() {
        val source = source()
        RepositoryFixture.scratch { f ->
            f.connect().use { c ->
                val binary = publish(f, c, source)
                c.execute(f.create(key = RepositoryTestKeys.compact(RepositoryTestKeys.second)), true)
                assertFailsWith<SQLException> { c.execute("INSTALL inet FROM private_repo", true) }
                c.execute(f.create().replace("CREATE EXTENSION", "CREATE OR REPLACE EXTENSION"), true)
                // The same bytes do not gain repository trust merely by referring to their path/URL.
                val direct = assertFailsWith<SQLException> { c.execute("LOAD ${quote(binary.toString())}", true) }
                assertTrue(direct.message.orEmpty().contains("signature", ignoreCase = true))
                assertFailsWith<SQLException> { c.execute("INSTALL inet FROM ${quote(f.prefix.toString())}", true) }
                c.execute("INSTALL inet FROM private_repo", true)
            }
            f.connect().use { c ->
                c.execute(f.create(key = RepositoryTestKeys.compact(RepositoryTestKeys.second)).replace("CREATE EXTENSION", "CREATE OR REPLACE EXTENSION"), true)
                assertFailsWith<SQLException> { c.execute("LOAD inet FROM private_repo", true) }
                val rotation = f.create().replace("CREATE EXTENSION", "CREATE OR REPLACE EXTENSION").replace("PUBLIC KEY", "PUBLIC KEYS") +
                    ", " + quote(RepositoryTestKeys.compact(RepositoryTestKeys.second))
                c.execute(rotation, true)
                c.execute("LOAD inet FROM private_repo", true)
                assertEquals("127.0.0.1", c.scalar("SELECT host('127.0.0.1'::INET)"))
            }
            f.connect("forbidden").use { c ->
                assertFailsWith<SQLException> { c.execute("LOAD inet FROM private_repo", true) }
                c.execute("DROP EXTENSION REPOSITORY private_repo", true)
                assertTrue(f.trustFiles().isEmpty())
                assertEquals("false", c.scalar("SELECT current_setting('allow_unsigned_extensions')"))
            }
        }
    }
}
