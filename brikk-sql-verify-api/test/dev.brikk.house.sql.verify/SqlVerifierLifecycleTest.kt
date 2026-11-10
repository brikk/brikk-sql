package dev.brikk.house.sql.verify

import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class SqlVerifierLifecycleTest {
    @Test
    fun closeIsAJvmDefaultMethod() {
        assertTrue(AutoCloseable::class.java.isAssignableFrom(SqlVerifier::class.java))
        assertTrue(SqlVerifier::class.java.getDeclaredMethod("close").isDefault)
    }

    @Test
    fun aStatelessImplementerInheritsNoOpCloseAndWorksInUse() {
        val verifier: SqlVerifier = object : SqlVerifier {
            override val engine = "stateless"
            override fun verify(sql: String): VerifyResult = VerifyResult.Accepted
            override fun verifyExpression(sql: String): VerifyResult = VerifyResult.Accepted
        }
        verifier.use { assertSame(VerifyResult.Accepted, it.verify("SELECT 1")) }
        verifier.close()
        assertSame(VerifyResult.Accepted, verifier.verifyExpression("1"))
    }

    @Test
    fun anImplementerCompiledAgainstTheOldInterfaceLinksAndInheritsClose() {
        val directory = createTempDirectory("verifier-old-interface")
        try {
            val oldApi = Files.createDirectory(directory.resolve("old-api"))
            val plugin = Files.createDirectory(directory.resolve("plugin"))
            // The pre-close contract, compiled separately: the plugin cannot see the new supertype
            // or method at compile time. At runtime its parent loader supplies only the current API.
            val apiSource = directory.resolve("SqlVerifier.java")
            Files.writeString(apiSource, """
                package dev.brikk.house.sql.verify;
                public interface SqlVerifier {
                    String getEngine();
                    default boolean getAdvisory() { return false; }
                    VerifyResult verify(String sql);
                    VerifyResult verifyExpression(String sql);
                }
            """.trimIndent())
            val pluginSource = directory.resolve("OldVerifier.java")
            Files.writeString(pluginSource, """
                package plugin;
                import dev.brikk.house.sql.verify.*;
                public final class OldVerifier implements SqlVerifier {
                    public String getEngine() { return "old-plugin"; }
                    public VerifyResult verify(String sql) { return VerifyResult.Accepted.INSTANCE; }
                    public VerifyResult verifyExpression(String sql) { return verify(sql); }
                }
            """.trimIndent())
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            val classpath = System.getProperty("java.class.path")
            assertEquals(0, compiler.run(null, null, null, "--release", "17", "-classpath", classpath,
                "-d", oldApi.toString(), apiSource.toString()))
            assertEquals(0, compiler.run(null, null, null, "--release", "17", "-classpath",
                "$oldApi${java.io.File.pathSeparator}$classpath", "-d", plugin.toString(), pluginSource.toString()))
            URLClassLoader(arrayOf(plugin.toUri().toURL()), SqlVerifier::class.java.classLoader).use { loader ->
                val type = loader.loadClass("plugin.OldVerifier")
                assertFalse(type.declaredMethods.any { it.name == "close" })
                val verifier = type.getConstructor().newInstance() as SqlVerifier
                assertEquals("old-plugin", verifier.engine)
                verifier.use { assertSame(VerifyResult.Accepted, it.verify("SELECT 1")) }
                verifier.close()
                assertSame(VerifyResult.Accepted, verifier.verifyExpression("1"))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
