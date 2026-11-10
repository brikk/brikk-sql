package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PostgresVerifierLifecycleTest {
    @Test
    fun validStatementAfterCloseIsUnavailableWithoutConnecting() = assertClosed { it.verify("SELECT 1") }

    @Test
    fun multipleStatementsAfterCloseAreUnavailableWithoutConnecting() = assertClosed { it.verify("SELECT 1; SELECT 2") }

    @Test
    fun emptyInputAfterCloseIsUnavailableWithoutConnecting() = assertClosed { it.verify("") }

    @Test
    fun expressionAfterCloseIsUnavailableWithoutConnecting() = assertClosed { it.verifyExpression("1") }

    private fun assertClosed(verify: (PostgresVerifier) -> VerifyResult) {
        var connections = 0
        val verifier = PostgresVerifier(connectionFactory = {
            connections++
            error("A closed verifier must not connect")
        })
        verifier.close()
        val result = assertIs<VerifyResult.Unavailable>(verify(verifier))
        assertEquals("PostgreSQL verification was not performed: verifier is closed.", result.reason)
        assertEquals(0, connections)
    }
}
