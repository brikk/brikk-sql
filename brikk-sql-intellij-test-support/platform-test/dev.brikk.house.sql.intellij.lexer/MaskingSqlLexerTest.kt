package dev.brikk.house.sql.intellij.lexer

import com.intellij.lexer.Lexer
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
import com.intellij.sql.dialects.postgres.PgLexer

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

    fun testAQuotedTryCastTargetIsMaskedUpToTheCallsParenthesis() {
        assertTokens("SELECT TRY_CAST(1 AS \"INTEGER\");", "TRY_CAST" to null, "(" to null, "1" to null,
            "AS \"INTEGER\"" to SqlTokens.SQL_BLOCK_COMMENT, ")" to null, ";" to null)
    }

    fun testTargetsOfAnyIdentifierStartAndAfterACommentAreMasked() {
        assertTokens("SELECT TRY_CAST(1 AS _int);", "AS _int" to SqlTokens.SQL_BLOCK_COMMENT, ")" to null, ";" to null)
        assertTokens("SELECT TRY_CAST(1 AS /* type */ INTEGER);", "AS /* type */ INTEGER" to SqlTokens.SQL_BLOCK_COMMENT, ")" to null, ";" to null)
    }

    fun testAMissingTryCastTargetKeepsTheDelimiters() {
        assertTokens("SELECT TRY_CAST(1 AS /* c */ ); SELECT 2", "TRY_CAST" to null, "1" to null, "AS" to null, "/* c */" to SqlTokens.SQL_BLOCK_COMMENT, ")" to null, ";" to null)
        assertTokens("SELECT TRY_CAST(1 AS , 2)", "TRY_CAST" to null, "1" to null, "AS" to null, "," to null)
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

    fun testAnUnterminatedRoutineBodyEndsBeforeItsFirstSemicolon() {
        val text = "CREATE FUNCTION f() BEGIN SET x = 1; IF x THEN SET y = 2;\nSELECT a FROM t;\nSELECT b FROM u"
        assertTokens(text, "BEGIN SET x = 1" to SqlTokens.SQL_STRING_TOKEN, ";" to null, "IF" to null, "SELECT" to null, "a" to null)
        assertRestartsMatchFullLex(::FixtureLexer, text)
    }

    fun testTypingInAnUnterminatedBodyKeepsTheLaterStatements() {
        configureFixture("CREATE FUNCTION f() BEGIN SET x = 1; END;\nSELECT a FROM t;\nSELECT b FROM u")
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.deleteString(document.text.indexOf("END;"), document.text.indexOf("END;") + 4) }
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.text.indexOf("x = 1"), "q") }
        val highlighter = (myFixture.editor as EditorEx).highlighter
        val later = document.text.indexOf("SELECT b")
        assertTrue(tokens(highlighter).any { it.first == later && it.third == "SELECT" })
        val fresh = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.virtualFile)
        fresh.setText(document.immutableCharSequence)
        assertEquals(tokens(fresh), tokens(highlighter))
    }

    fun testTheStatementAfterEachSemicolonStartsAtARestartPoint() {
        val initial = FixtureLexer().run { start(""); state }
        val tokens = FixtureLexer().lexAll(MIXED).filter { it.type != TokenType.WHITE_SPACE }
        val after = tokens.zipWithNext().filter { (token, _) -> MIXED.substring(token.start, token.end) == ";" }.map { it.second }
        assertEquals(3, after.size)
        assertEquals(after.map { initial }, after.map { it.state })
    }

    fun testRestoringAPositionKeepsTheStatementContext() {
        val text = "SELECT 1; SELECT * EXCLUDE (a) FROM t; SELECT 2"
        val lexer = FixtureLexer()
        lexer.start(text)
        while (lexer.tokenStart != text.indexOf("EXCLUDE")) lexer.advance()
        val position = lexer.currentPosition
        repeat(6) { lexer.advance() }
        lexer.restore(position)
        assertEquals(SqlTokens.SQL_BLOCK_COMMENT, lexer.tokenType)
        assertEquals(text.indexOf(" FROM"), lexer.tokenEnd)
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

/** Casts whose target is masked through its type, keeping the INT targets the grammar knows. */
class TypeCastLexer : MaskingSqlLexer(PgLexer(), CastTargetMask(setOf("CAST", "TRY_CAST"), setOf("INT"), CastTargetMask.Extent.TargetType)) {
    override fun rule(base: Lexer, upper: String): Boolean = false
}

class CastTargetMaskTest : FixtureTestCase() {
    private fun nonBlank(text: String) =
        TypeCastLexer().lexAll(text).filter { it.type != TokenType.WHITE_SPACE }.map { text.substring(it.start, it.end) to it.type }

    fun testTheTypeAndItsArgumentsAreMasked() {
        val tokens = nonBlank("SELECT CAST(a AS ARRAY<MAP<INT, INT>>) , TRY_CAST(b AS DECIMAL(10, 2)), CAST(c AS INT)")
        assertContainsElements(tokens, "AS ARRAY<MAP<INT, INT>>" to SqlTokens.SQL_BLOCK_COMMENT, "AS DECIMAL(10, 2)" to SqlTokens.SQL_BLOCK_COMMENT)
        assertTrue(tokens.any { it.first == "AS" })
    }

    fun testAMissingTargetConsumesNeitherSemicolonNorParenthesis() {
        val semicolon = nonBlank("SELECT TRY_CAST(x AS ; SELECT 1").map { it.first }
        assertEquals(listOf("SELECT", "TRY_CAST", "(", "x", "AS", ";", "SELECT", "1"), semicolon)
        val paren = nonBlank("SELECT CAST(x AS) FROM t").map { it.first }
        assertEquals(listOf("SELECT", "CAST", "(", "x", "AS", ")", "FROM", "t"), paren)
    }

    fun testUnderscoreAndCommentedTargetsAreMasked() {
        val tokens = nonBlank("SELECT CAST(x AS _int), CAST(y AS /* t */ ARRAY<INT>)").map { it.first }
        assertContainsElements(tokens, "AS _int", "AS /* t */ ARRAY<INT>")
    }

    fun testAQuotedTargetIsMasked() {
        assertContainsElements(nonBlank("SELECT CAST(x AS \"INTEGER\"), 1").map { it.first }, "AS \"INTEGER\"", ")", ",")
    }

    fun testParenthesesInsideAngleBracketsAreBalanced() {
        val tokens = nonBlank("SELECT CAST(x AS ARRAY<VARCHAR(120)>) FROM db.t").map { it.first }
        assertEquals(listOf("SELECT", "CAST", "(", "x", "AS ARRAY<VARCHAR(120)>", ")", "FROM", "db", ".", "t"), tokens)
        val nested = nonBlank("SELECT CAST(x AS MAP<VARCHAR(10), ARRAY<DECIMAL(10, 2)>>), 1").map { it.first }
        assertContainsElements(nested, "AS MAP<VARCHAR(10), ARRAY<DECIMAL(10, 2)>>", ")", ",")
        // An unclosed bracket still stops at the cast's own parenthesis.
        assertEquals(listOf("SELECT", "CAST", "(", "x", "AS ARRAY<VARCHAR(120)", ")", "FROM", "t"),
            nonBlank("SELECT CAST(x AS ARRAY<VARCHAR(120)) FROM t").map { it.first })
    }

    fun testAnUnclosedTypeArgumentStopsAtTheStatementEnd() {
        val tokens = nonBlank("SELECT CAST(x AS ARRAY<INT ; SELECT 1").map { it.first.trim() }
        assertEquals(listOf("SELECT", "CAST", "(", "x", "AS ARRAY<INT", ";", "SELECT", "1"), tokens)
    }
}
