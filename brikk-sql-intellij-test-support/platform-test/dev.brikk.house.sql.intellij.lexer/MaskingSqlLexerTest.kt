package dev.brikk.house.sql.intellij.lexer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.fixture.FixtureLexer
import dev.brikk.house.sql.intellij.fixture.FixtureTestCase
import dev.brikk.house.sql.intellij.testing.assertRestartsMatchFullLex
import dev.brikk.house.sql.intellij.testing.lexAll

class MaskingSqlLexerTest : FixtureTestCase() {
    /** Asserts that each fragment, found in order, is exactly one token, of [type] when given. */
    private fun assertTokens(text: String, vararg expected: Pair<String, IElementType?>) {
        val tokens = FixtureLexer().lexAll(text).filter { it.type != TokenType.WHITE_SPACE }
        var from = 0
        for ((fragment, type) in expected) {
            val start = text.indexOf(fragment, from)
            val token = tokens.firstOrNull { it.start == start }
            assertEquals("token at '$fragment'", start + fragment.length, token?.end)
            if (type != null) assertEquals("type of '$fragment'", type, token?.type)
            from = start + fragment.length
        }
    }

    fun testRulesCollapseAndMaskExactSpans() {
        assertTokens(
            "SELECT * EXCLUDE (a, b), x::STRUCT(a INT), {'k': 1} FROM t AT LOCAL",
            "*" to null,
            "EXCLUDE (a, b)" to SqlTokens.SQL_BLOCK_COMMENT,
            "," to null,
            "x" to null,
            "STRUCT(a INT)" to SqlTokens.SQL_IDENT,
            "{'k': 1}" to SqlTokens.SQL_STRING_TOKEN,
            "FROM" to null,
            "t" to null,
            "AT LOCAL" to SqlTokens.SQL_BLOCK_COMMENT,
        )
    }

    fun testTryCastTargetIsMaskedUpToTheCallsParenthesis() {
        assertTokens("SELECT TRY_CAST(f(a) AS DECIMAL(10, 2)) FROM t", "TRY_CAST" to null, "(" to null, "f" to null,
            "(" to null, "a" to null, ")" to null, "AS DECIMAL(10, 2)" to SqlTokens.SQL_BLOCK_COMMENT, ")" to null)
    }

    fun testAnUnclosedTryCastDoesNotMaskTheNextStatementsCast() {
        assertTokens("SELECT TRY_CAST(a; SELECT CAST(b AS INT)", "TRY_CAST" to null, ";" to null, "CAST" to null, "(" to null,
            "b" to null, "AS" to null, "INT" to null)
    }

    fun testCommentsDoNotChangeTheLastMeaningfulToken() {
        assertTokens("SELECT * /* all but */ EXCLUDE (a) FROM t", "/* all but */" to SqlTokens.SQL_BLOCK_COMMENT,
            "EXCLUDE (a)" to SqlTokens.SQL_BLOCK_COMMENT, "FROM" to null)
    }

    fun testRoutineBodyIsOneTokenAtAnyLength() {
        val body = (1..3000).joinToString(" ") { "SET x = $it;" }
        val text = "CREATE FUNCTION f() BEGIN $body END; SELECT 1"
        assertTokens(text, "BEGIN $body END" to SqlTokens.SQL_STRING_TOKEN, ";" to null, "SELECT" to null)
    }

    fun testRoutineContextEndsWithTheStatement() {
        assertTokens("CREATE FUNCTION f() BEGIN END; SELECT 1; BEGIN", "BEGIN END" to SqlTokens.SQL_STRING_TOKEN, "BEGIN" to null)
    }

    fun testEveryRestartMatchesTheFullLex() {
        assertRestartsMatchFullLex(::FixtureLexer, MIXED)
    }

    fun testEditorHighlightingAfterAnEditMatchesAFreshLex() {
        configureFixture(MIXED)
        val highlighter = (myFixture.editor as EditorEx).highlighter
        assertTrue("the editor lexes with the fixture lexer", tokens(highlighter).any { it.second == SqlTokens.SQL_STRING_TOKEN && it.third.startsWith("BEGIN") })
        val document = myFixture.editor.document
        for (edit in listOf("f()", "x = 1;", "EXCLUDE")) {
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.text.indexOf(edit) + 1, "q") }
            val fresh = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.virtualFile)
            fresh.setText(document.immutableCharSequence)
            assertEquals("after editing '$edit'", tokens(fresh), tokens(highlighter))
        }
    }

    private fun tokens(highlighter: EditorHighlighter): List<Triple<Int, IElementType, String>> = buildList {
        val iterator = highlighter.createIterator(0)
        val text = myFixture.editor.document.immutableCharSequence
        while (!iterator.atEnd()) {
            add(Triple(iterator.start, iterator.tokenType, text.subSequence(iterator.start, iterator.end).toString()))
            iterator.advance()
        }
    }

    private companion object {
        val MIXED = """
            SELECT * EXCLUDE (a, b) FROM t;
            CREATE FUNCTION f() BEGIN SET x = 1; IF x THEN SET y = 2; END IF; END;
            SELECT TRY_CAST(a AS INT), {'k': ';'} FROM t /* ; */ AT LOCAL;
            SELECT x::STRUCT(a INT) FROM t
        """.trimIndent()
    }
}
