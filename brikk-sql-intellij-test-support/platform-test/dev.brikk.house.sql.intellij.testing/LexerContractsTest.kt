package dev.brikk.house.sql.intellij.testing

import com.intellij.lexer.LookAheadLexer
import com.intellij.sql.dialects.postgres.PgLexer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Reports a mid-statement state at every token, so only an empty text starts in the initial state. */
class NeverRestartingLexer : LookAheadLexer(PgLexer()) {
    override fun getState(): Int = super.getState().let { if (tokenType == null) it else it or Int.MIN_VALUE }
}

class LexerContractsTest : BasePlatformTestCase() {
    fun testALexerWithoutRestartPointsFails() {
        val failure = try {
            assertRestartsMatchFullLex(::NeverRestartingLexer, "SELECT 1; SELECT 2")
            null
        } catch (expected: AssertionError) {
            expected
        }
        assertNotNull(failure)
    }

    fun testOneStatementNeedsNoRestartPoint() {
        assertRestartsMatchFullLex(::NeverRestartingLexer, "SELECT 1")
    }
}
