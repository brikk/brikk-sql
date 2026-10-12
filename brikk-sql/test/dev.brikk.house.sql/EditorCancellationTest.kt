package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.DorisDialect
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.shape.SqlScriptParameters
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorCancellationTest {

    private class Stop : RuntimeException()

    private val script = (1..2_000).joinToString("\n") { "SELECT a$it, :p$it FROM t WHERE x = 'v';" }

    private fun cancelsWith(run: (() -> Unit) -> Unit) {
        val stop = Stop()
        var calls = 0
        run { calls++ }
        assertTrue(calls > 0, "the hook is never called")
        assertSame(stop, assertFailsWith<Stop> { run { throw stop } })
    }

    @Test
    fun editorTokenizationPropagatesTheExactCancellation() =
        cancelsWith { check -> Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenizeForEditor(script, check) }

    @Test
    fun snapshotPropagatesTheExactCancellation() = cancelsWith { check -> SqlScriptSnapshot.parse(script, "doris", check) }

    @Test
    fun editorParametersPropagateTheExactCancellation() =
        cancelsWith { check -> SqlScriptParameters.parse(script, "doris", check) }

    @Test
    fun tokenizerIsReusableAfterCancellation() {
        val tokenizer = Tokenizer(DorisDialect.TOKENIZER_CONFIG)
        assertFailsWith<Stop> { tokenizer.tokenizeForEditor(script) { throw Stop() } }
        assertTrue(tokenizer.tokenizeForEditor("SELECT 1").tokens.size == 2)
        assertTrue(tokenizer.tokenizeForEditor(script).failureOffset == null)
    }

    @Test
    fun longTokensAndCommentRecoveryAreCancellable() {
        val long = "a".repeat(1_000_000)
        for (sql in listOf(
            "SELECT '$long'",
            "SELECT /* $long */ 1",
            " ".repeat(1_000_000) + "SELECT 1",
            "\n".repeat(1_000_000),
            "SELECT 1 -- $long",
            "SELECT 1 /* a /*/ $long b */ c",
        )) {
            var calls = 0
            Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenizeForEditor(sql) { calls++ }
            assertTrue(calls >= 10, "${sql.take(20)}: hook called $calls times")
            val stop = Stop()
            assertSame(stop, assertFailsWith<Stop> { Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenizeForEditor(sql) { throw stop } })
        }
    }

    @Test
    fun aTokenErrorFromTheHookIsNotALexicalFailure() {
        val signal = TokenError("canceled")
        assertSame(signal, assertFailsWith<TokenError> { Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenizeForEditor(script) { throw signal } })
        assertSame(signal, assertFailsWith<TokenError> { SqlScriptSnapshot.parse(script, "doris") { throw signal } })
        assertSame(signal, assertFailsWith<TokenError> { SqlScriptParameters.parse(script, "doris") { throw signal } })
    }
}
