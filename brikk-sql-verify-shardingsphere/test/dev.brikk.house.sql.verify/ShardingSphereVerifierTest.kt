package dev.brikk.house.sql.verify

import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the advisory ShardingSphere grammar oracle's fidelity findings for the four dialects and
 * that every verifier is [SqlVerifier.advisory]. These are the documented limitations of the
 * advisory tier — not bugs to "fix" in this module.
 */
internal class ShardingSphereVerifierTest {

    @Test
    fun parsersLoadUnderAForeignContextClassLoader() {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        val foreign = URLClassLoader(emptyArray(), null)
        thread.contextClassLoader = foreign
        try {
            for (engine in listOf("hive", "mysql")) {
                val v = verifier(engine)
                assertIs<VerifyResult.Accepted>(v.verify("SELECT * FROM t"), engine)
                assertSame(foreign, thread.contextClassLoader)
            }
        } finally {
            thread.contextClassLoader = previous
        }
    }

    // -- postgres -------------------------------------------------------------------------

    @Test
    fun postgresAcceptsValidAndRejectsInvalid() {
        val v = verifier("postgres")
        assertIs<VerifyResult.Accepted>(v.verify("SELECT * FROM users WHERE metadata->>'role' = 'admin'"))
        assertIs<VerifyResult.Rejected>(v.verify("SELECT FROM WHERE"))
    }

    // -- mysql ----------------------------------------------------------------------------

    @Test
    fun mysqlAcceptsValidAndRejectsInvalid() {
        val v = verifier("mysql")
        assertIs<VerifyResult.Accepted>(v.verify("SELECT `col` FROM t LIMIT 5"))
        assertIs<VerifyResult.Rejected>(v.verify("SELECT FROM WHERE"))
    }

    // -- hive -----------------------------------------------------------------------------

    @Test
    fun hiveAcceptsValidAndRejectsInvalid() {
        val v = verifier("hive")
        assertIs<VerifyResult.Accepted>(v.verify("SELECT * FROM t"))
        assertIs<VerifyResult.Rejected>(v.verify("SELECT FROM WHERE"))

        // Advisory limitation: Hive grammar false-rejects valid LATERAL VIEW. Pinned here so the
        // limitation is documented; do NOT treat this as a bug of the advisory tier.
        assertIs<VerifyResult.Rejected>(
            v.verify("SELECT t.c FROM src LATERAL VIEW EXPLODE(arr) t AS c"),
            "known advisory false-reject of LATERAL VIEW",
        )
    }

    // -- clickhouse -----------------------------------------------------------------------

    @Test
    fun clickhouseAcceptsValidAndIsAdvisory() {
        // Advisory limitation: the ClickHouse grammar false-ACCEPTS invalid `SELECT FROM WHERE`,
        // which is exactly why ClickHouse is advisory-only here (use the chDB ClickhouseVerifier
        // in brikk-sql-oracle for engine-exact fidelity). Not asserted as a rejection.
        assertIs<VerifyResult.Accepted>(verifier("clickhouse").verify("SELECT lowerUTF8(x) FROM t"))
    }

    @Test
    fun expressionsAreCheckedInsideSelect() {
        val v = verifier("postgres")
        assertIs<VerifyResult.Accepted>(v.verifyExpression("metadata->>'role'"))
        assertIs<VerifyResult.Rejected>(v.verifyExpression("1 +"))
    }

    // -- registry -------------------------------------------------------------------------

    @Test
    fun everyEngineIsAdvisoryAndUnknownEnginesHaveNone() {
        for (engine in listOf("postgres", "mysql", "hive", "clickhouse")) {
            val verifier = verifier(engine.uppercase())
            assertEquals(engine, verifier.engine)
            assertTrue(verifier.advisory, "$engine should be advisory")
        }
        assertNull(ShardingSphereVerifier.forEngine("nope"))
    }

    private fun verifier(engine: String): ShardingSphereVerifier =
        assertIs<ShardingSphereVerifier>(ShardingSphereVerifier.forEngine(engine))
}
