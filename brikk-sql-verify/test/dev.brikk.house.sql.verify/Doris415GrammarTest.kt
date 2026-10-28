package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exact 4.1.5-release grammar, not server-side validation or execution. */
class Doris415GrammarTest {
    private val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))

    private fun acceptsRoundTrip(sql: String) {
        val fragment = SqlFragment(sql, "doris")
        assertFalse(fragment.ast is Command, sql)
        val generated = fragment.transpileTo("doris")
        assertTrue(generated.unsupportedMessages.isEmpty(), generated.unsupportedMessages.toString())
        for (text in listOf(sql, generated.sql)) {
            assertIs<VerifyResult.Accepted>(verifier.verify(text), text)
        }
        assertEquals(generated.sql, SqlFragment(generated.sql, "doris").transpileTo("doris").sql)
    }

    @Test
    fun lanceIndexFormsAndUnreservedBtreeRoundTrip() {
        // OR REPLACE, BTREE and BITMAP are Lance-only at execution time.
        for (type in listOf("BTREE", "BITMAP", "INVERTED", "ANN")) {
            acceptsRoundTrip("CREATE OR REPLACE INDEX idx ON lance.db.t (c) USING $type")
            acceptsRoundTrip("CREATE INDEX IF NOT EXISTS idx ON lance.db.t (c) USING $type")
        }
        acceptsRoundTrip("SELECT btree FROM btree")
        acceptsRoundTrip("CREATE INDEX idx ON t (c) USING INVERTED PROPERTIES ('norms'='false')")
    }

    @Test
    fun newTableFunctionsAndVectorSearchPropertiesRoundTrip() {
        acceptsRoundTrip("SELECT * FROM FULL_TEXT_SEARCH('table'='lance.db.t', 'column'='body', 'query'='hello', " +
            "'query_type'='phrase', 'slop'='1', 'version'='3')")
        acceptsRoundTrip("SELECT * FROM LANCE_INDEX_ENTRIES('table'='lance.db.t')")
        acceptsRoundTrip("SELECT * FROM VECTOR_SEARCH('table'='lance.db.t', 'column'='embedding', " +
            "'query_vector'='[0,1]', 'prefilter'='true', 'query_parallelism'='2', 'distance_upper_bound'='0.5')")
        acceptsRoundTrip("INSERT OVERWRITE TABLE hive.db.t PARTITION (dt='2026-10-28') SELECT 1")
    }

    @Test
    fun udfCreationAcceptsTypesButRejectsRemovedEllipsisForms() {
        assertIs<VerifyResult.Accepted>(verifier.verify(
            "CREATE FUNCTION f(INT, STRING) RETURNS STRING PROPERTIES ('symbol'='example.Function')",
        ))
        assertIs<VerifyResult.Accepted>(verifier.verify(
            "CREATE ALIAS FUNCTION f(INT) WITH PARAMETER (x) AS x + 1",
        ))
        for (arguments in listOf("...", "INT, ...")) {
            assertIs<VerifyResult.Rejected>(verifier.verify(
                "CREATE FUNCTION f($arguments) RETURNS INT PROPERTIES ('symbol'='example.Function')",
            ))
            assertIs<VerifyResult.Rejected>(verifier.verify(
                "CREATE ALIAS FUNCTION f($arguments) WITH PARAMETER (x) AS x + 1",
            ))
        }
        // DROP still accepts the old functionArguments production.
        assertIs<VerifyResult.Accepted>(verifier.verify("DROP FUNCTION f(INT, ...)"))
    }
}
