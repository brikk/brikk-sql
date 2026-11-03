package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class Clickhouse2026VerifierSafetyTest {
    @Test
    fun executingExplainAndCredentialDdlAreOnlyHexDataInTheSubmittedQuery() {
        val statements = listOf(
            "EXPLAIN ANALYZE INSERT INTO db.t SELECT 1",
            "EXPLAIN ANALYZE SELECT * FROM url('https://invalid.test/marker', 'TSV', 'x UInt8')",
            "CREATE TOKEN VALID FOR INTERVAL 1 DAY GRANTS (SELECT ON db.t)",
            "CREATE USER u IDENTIFIED WITH sha256_password BY 'fake-secret' GRANTS (SELECT ON db.t)",
            "CREATE HANDLER h URL '/test' METHODS (POST) AS INSERT INTO db.t SELECT 1",
            "SYSTEM PAUSE ALL BACKGROUND",
        )
        for (source in statements) {
            val submitted = mutableListOf<String>()
            var closed = false
            val verifier = ParseOnlyClickhouseVerifier(connect = {
                object : ClickhouseQueryConnection {
                    override fun queryString(sql: String, timeoutSeconds: Int): String {
                        submitted.add(sql)
                        return "formatted fixture"
                    }
                    override fun cancel() = Unit
                    override fun close() { closed = true }
                }
            })
            assertEquals(VerifyResult.Accepted, verifier.verify(source))
            val query = submitted.single()
            val hex = source.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it.toInt() and 255) }
            assertEquals("SELECT formatQuery(unhex('$hex')) SETTINGS readonly = 1, max_execution_time = 5", query)
            assertFalse("fake-secret" in query)
            assertFalse("EXPLAIN ANALYZE" in query)
            assertTrue(closed)
        }
    }
}
