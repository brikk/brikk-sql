package dev.brikk.house.sql.intellij.testing

import com.intellij.lexer.Lexer
import com.intellij.psi.tree.IElementType

/** One token of a lexing pass: its range, type, and the state the lexer reported at it. */
public data class LexedToken(val start: Int, val end: Int, val type: IElementType, val state: Int)

/** Every token of [text], lexed from [startOffset] in [initialState]. */
public fun Lexer.lexAll(text: CharSequence, startOffset: Int = 0, initialState: Int = 0): List<LexedToken> {
    start(text, startOffset, text.length, initialState)
    return buildList {
        while (true) {
            val type = tokenType ?: break
            add(LexedToken(tokenStart, tokenEnd, type, state))
            advance()
        }
    }
}

/**
 * Lexes [text] in full, then again from every token at which the lexer reports its initial state,
 * as the editor highlighter restarts it after an edit, and fails unless each restarted pass yields
 * the same tokens and states as the full pass from there on.
 */
public fun assertRestartsMatchFullLex(newLexer: () -> Lexer, text: CharSequence) {
    val initial = newLexer().run {
        start("")
        state
    }
    val full = newLexer().lexAll(text, 0, initial)
    for ((index, token) in full.withIndex()) {
        if (index == 0 || token.state != initial) continue
        val restarted = newLexer().lexAll(text, token.start, initial)
        val expected = full.subList(index, full.size)
        if (restarted != expected) {
            val at = restarted.zip(expected).indexOfFirst { (a, b) -> a != b }.takeIf { it >= 0 } ?: minOf(restarted.size, expected.size)
            throw AssertionError("A restart at ${token.start} diverges at token $at: ${restarted.getOrNull(at)} instead of ${expected.getOrNull(at)}")
        }
    }
}
