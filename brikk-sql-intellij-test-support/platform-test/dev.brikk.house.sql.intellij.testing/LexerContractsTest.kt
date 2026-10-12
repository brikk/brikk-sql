package dev.brikk.house.sql.intellij.testing

import com.intellij.lexer.LookAheadLexer
import com.intellij.sql.dialects.postgres.PgLexer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Reports a mid-statement state at every token, so only an empty text starts in the initial state. */
class NeverRestartingLexer : LookAheadLexer(PgLexer()) {
    override fun getState(): Int = super.getState().let { if (tokenType == null) it else it or 0x4000_0000 }
}

/** Marks statements with the sign bit, as the shared lexer once did. */
class NegativeStateLexer : LookAheadLexer(PgLexer()) {
    override fun getState(): Int = super.getState().let { if (tokenType == null || tokenStart == 0) it else it or Int.MIN_VALUE }
}

class LexerContractsTest : BasePlatformTestCase() {
    private fun failure(check: () -> Unit): AssertionError? = try {
        check()
        null
    } catch (expected: AssertionError) {
        expected
    }

    fun testANegativeStateFails() {
        assertNotNull(failure { assertRestartsMatchFullLex(::NegativeStateLexer, "SELECT 1") })
    }

    fun testALexerWithoutRestartPointsFails() {
        assertNotNull(failure { assertRestartsMatchFullLex(::NeverRestartingLexer, "SELECT 1; SELECT 2") })
    }

    fun testOneStatementNeedsNoRestartPoint() {
        assertRestartsMatchFullLex(::NeverRestartingLexer, "SELECT 1")
    }
}
