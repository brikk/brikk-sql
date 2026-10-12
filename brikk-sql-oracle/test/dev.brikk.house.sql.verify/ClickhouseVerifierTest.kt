package dev.brikk.house.sql.verify

import dev.brikk.house.chdb.ChdbConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun bytePositionsInTheWrappedQueryBecomeUtf16RangesAcrossCrlf() {
        for (payload in listOf("ascii", "\uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE80")) {
            for (separator in listOf(" ", "\n", "\r\n")) {
                val sql = "SELECT '$payload',$separator'$payload' ) FROM t"
                val paren = sql.indexOf(')')
                // ClickHouse's 1-based UTF-8 byte position in the query it parsed, EXPLAIN AST included.
                val position = "EXPLAIN AST ${sql.take(paren)}".toByteArray(Charsets.UTF_8).size + 1
                val message = "Code: 62. DB::Exception: Syntax error: failed at position $position ()): ) FROM t. " +
                    "Unmatched parentheses: ). (SYNTAX_ERROR)"
                val error = assertIs<VerifyResult.Rejected>(classifyClickhouseFailure(sql, message)).error
                assertEquals(
                    listOf(sql.take(paren).count { it == '\n' } + 1, paren - (sql.lastIndexOf('\n', paren - 1) + 1) + 1, paren),
                    listOf(error.line, error.column, error.offset),
                    sql,
                )
            }
        }
        assertIs<VerifyResult.Accepted>(classifyClickhouseFailure("SELECT x FROM t", "Code: 60. DB::Exception: Unknown table"))
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
            val sql = "SELECT 1,\r\n'\uD83D\uDE00' ) FROM t"
            assertEquals(sql.indexOf(')'), assertIs<VerifyResult.Rejected>(verifier.verify(sql)).error.offset)
        }
    }
}
