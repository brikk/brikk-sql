package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbAnalyseTest {
    @Test fun nativeSynonymsUseTheSameStructuredAstAndPreserveColumnLists() {
        assertEquals(TokenType.ANALYZE, nativeDuckdbAnalysisDialect().tokenize("ANALYSE").single().tokenType)
        assertEquals(TokenType.IDENTIFIER, nativeDuckdbAnalysisDialect().tokenize("\"analyse\"").single().tokenType)
        for (tail in listOf("", " t", " memory.t(x,y)", " \"odd.name\"(\"x.y\")", " VERBOSE t")) {
            val tree = assertIs<Analyze>(parseDuckdbNativeOne("ANALYSE$tail"))
            assertTrue(tree.findAll<Anonymous>().none(), "Statistics column lists are not function calls")
            assertEquals(tree, parseDuckdbNativeOne("ANALYZE$tail"))
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val generated = tree.copy().sql("duckdb")
            assertTrue(generated.startsWith("ANALYZE"), generated)
            assertEquals(tree, parseDuckdbNativeOne(generated))
            assertFalse(SqlFragment("ANALYSE$tail", "duckdb").toExecutable("duckdb").isRawPassthroughStatement)
            assertFailsWith<UnsupportedError> { SqlFragment("ANALYSE$tail", "duckdb").toExecutable("trino") }
        }
        val sql = "ANALYSE; ANALYSE t(x); SELECT 7"
        val statements = nativeDuckdbParser().parse(nativeDuckdbAnalysisDialect().tokenize(sql), sql).filterNotNull().filterNot { it is Semicolon }
        assertEquals(listOf("Analyze", "Analyze", "Select"), statements.map { it::class.simpleName })
        assertIs<Select>(parseDuckdbNativeOne("SELECT \"analyse\" FROM \"analyse\""))
    }
    @Test fun unsupportedClausesAreNotSilentlyDiscardedAndExplainKeepsItsExistingStructure() {
        for (sql in listOf("ANALYSE t()", "ANALYSE t(x,)", "ANALYSE t,t", "ANALYSE TABLE t", "ANALYSE (VERBOSE) t", "ANALYSE t(x) extra"))
            assertFails(sql) { parseDuckdbNativeOne(sql) }
        assertIs<DuckdbExplain>(parseDuckdbNativeOne("EXPLAIN ANALYSE SELECT 1"))
        assertIs<DuckdbExplain>(parseDuckdbNativeOne("EXPLAIN (ANALYSE) SELECT 1"))
        // VACUUM already has an explicit raw-passthrough contract, not a new Analyze AST.
        assertTrue(SqlFragment("VACUUM ANALYSE t", "duckdb").toExecutable("duckdb").isRawPassthroughStatement)
    }
}
