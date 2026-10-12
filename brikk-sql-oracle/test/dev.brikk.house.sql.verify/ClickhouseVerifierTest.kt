package dev.brikk.house.sql.verify

import dev.brikk.house.chdb.ChdbConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.api.Assumptions.assumeTrue

class ClickhouseVerifierTest {
    @Test
    fun unavailableNativeLibraryReturnsWarningInsteadOfThrowing() {
        // The regular focused test invocation deliberately has no libchdb. The verifier is
        // opt-in until brikk-chdb packages platform archives, rather than starting a CLI/server.
        // Constructed directly: the chDB verifier is a fidelity oracle, no longer registered in
        // SqlVerifiers.forEngine (which now returns the advisory ShardingSphere verifier).
        if (System.getProperty("brikk.chdb.integrationLibrary") == null) {
            assertIs<VerifyResult.Unavailable>(ClickhouseVerifier.create().verify("SELECT 1"))
        }
    }

    @Test
    fun nativeGrammarSmokeTestWhenLibraryIsConfigured() {
        // The same explicit-path property used by brikk-chdb's optional local integration test.
        val library = System.getProperty("brikk.chdb.integrationLibrary")
        assumeTrue(library != null, "brikk.chdb.integrationLibrary is not configured")
        ClickhouseVerifier.create(ChdbConfig(libraryPath = Path.of(library!!))).use { verifier ->
            assertIs<VerifyResult.Accepted>(verifier.verify("SELECT number FROM numbers(3)"))
            assertFalse(verifier.verify("SELECT FROM WHERE") is VerifyResult.Accepted)
            assertIs<VerifyResult.Accepted>(verifier.verifyExpression("arrayMap(x -> x + 1, [1, 2])"))
            assertFalse(verifier.verifyExpression("1 +") is VerifyResult.Accepted)
        }
    }
}
