package dev.brikk.house.sql.intellij.lexer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.sql.dialects.clickhouse.CHouseLexer
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.fixture.CHOUSE_BOUNDARY_CORPUS
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase
import dev.brikk.house.sql.intellij.testing.LexedToken
import dev.brikk.house.sql.intellij.testing.assertRestartsMatchFullLex
import dev.brikk.house.sql.intellij.testing.lexAll
import dev.brikk.house.sql.parser.SqlScriptSnapshot

class StatementBoundedSqlLexerTest : CHouseFixtureTestCase() {
    private fun bounded() = StatementBoundedSqlLexer(::CHouseLexer, "clickhouse")

    private fun String.of(token: LexedToken) = substring(token.start, token.end)

    private fun List<LexedToken>.raw() = filter { it.type == SqlTokens.SQL_RAW_INPUT }

    private fun List<LexedToken>.semicolons(text: String) = filter { text.of(it) == ";" }.map { it.start }

    fun testRawDataEndsWithItsStatement() {
        val text = "INSERT INTO events FORMAT JSONEachRow\n{\"id\":1};\nSELECT 2;"
        // The platform lexer reads the rest of the text as data.
        assertEquals("{\"id\":1};\nSELECT 2;", text.of(CHouseLexer().lexAll(text).raw().single()))
        val tokens = bounded().lexAll(text)
        assertEquals("{\"id\":1}", text.of(tokens.raw().single()))
        assertEquals(listOf(text.indexOf(";"), text.lastIndex), tokens.semicolons(text))
        assertTrue(tokens.any { text.of(it) == "SELECT" })
    }

    fun testRawDataOnOneLineEndsWithItsStatement() {
        val text = "INSERT INTO events FORMAT JSONEachRow {\"id\":1}; SELECT 2;"
        assertEquals("{\"id\":1}; SELECT 2;", text.of(CHouseLexer().lexAll(text).raw().single()))
        assertEquals("{\"id\":1}", text.of(bounded().lexAll(text).raw().single()))
    }

    fun testRawDataKeepsSemicolonsInsideItsValues() {
        val case = CHOUSE_BOUNDARY_CORPUS.single { it.name == "json data tail" }
        val tokens = bounded().lexAll(case.sql)
        assertEquals("{\"id\":1,\"label\":\"a; b\"}\n{\"id\":2,\"label\":\"c\"}", case.sql.of(tokens.raw().single()))
        assertEquals(listOf(case.sql.lastIndex), tokens.semicolons(case.sql))
    }

    fun testStatementsAfterRawDataParseOnTheirOwn() {
        for (name in listOf("json followed by select", "json on one line followed by select", "values followed by select", "json data tail")) {
            val case = CHOUSE_BOUNDARY_CORPUS.single { it.name == name }
            configureCHouse(case.sql)
            assertEquals(name, case.statements.map { it.removeSuffix(";") }, statements().map { it.text })
        }
    }

    fun testStatesAreNonNegativeAndZeroExactlyAtStatementStarts() {
        val text = "INSERT INTO FUNCTION s3(concat('a', toString((1))), 'x') SELECT 1;\n" + CHOUSE_BOUNDARY_CORPUS.joinToString("\n") { it.sql }
        // The platform lexer keeps its INSERT state in the top bits, which turn negative in nested parentheses.
        assertTrue(CHouseLexer().lexAll(text).any { it.state < 0 })
        val tokens = bounded().lexAll(text)
        assertTrue(tokens.all { it.state >= 0 })
        val starts = SqlScriptSnapshot.parse(text, "clickhouse").statements.map { it.start }
        assertEquals(starts, tokens.filter { it.state == 0 }.map { it.start })
    }

    fun testRestartsAtStatementStartsMatchAFullLex() {
        for (case in CHOUSE_BOUNDARY_CORPUS) assertRestartsMatchFullLex(::bounded, case.sql)
    }

    fun testRestoringAPositionRelexesFromItsStatement() {
        val text = "SELECT 1;\nINSERT INTO events FORMAT JSONEachRow\n{\"id\":1};\nSELECT 2;"
        val lexer = bounded()
        val full = lexer.lexAll(text)
        lexer.start(text)
        while (lexer.tokenType != null && text.substring(lexer.tokenStart, lexer.tokenEnd) != "JSONEachRow") lexer.advance()
        val position = lexer.currentPosition
        while (lexer.tokenType != null) lexer.advance()
        lexer.restore(position)
        val resumed = buildList {
            while (true) {
                add(LexedToken(lexer.tokenStart, lexer.tokenEnd, lexer.tokenType ?: break, lexer.state))
                lexer.advance()
            }
        }
        assertEquals(full.dropWhile { it.start < position.offset }, resumed)
    }

    /** Edits that turn text into data and back leave the editor's tokens as a fresh highlighter's. */
    fun testIncrementalHighlightingMatchesAFreshPass() {
        configureCHouse("INSERT INTO events FORMAT JSONEachRow\n{\"id\":1};\nSELECT 2;\nSELECT 3 FROM t;")
        val document = myFixture.editor.document
        val edits = listOf<(String) -> Pair<Int, String>>(
            { it.indexOf("1}") to "1, \"x\":\"; SELECT 9\"" },
            { it.indexOf("FORMAT") to "" },
            { it.indexOf("2;") + 2 to "\nINSERT INTO t FORMAT Values (1, ';')" },
            { it.indexOf("events") + 6 to " FORMAT TSV\n" },
            { it.lastIndexOf("t;") to "s3('x', 'y') PARTITION BY a\nSELECT 4 " },
        )
        for (edit in edits) {
            val (offset, insert) = edit(document.text)
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(offset, insert) }
            val fresh = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.virtualFile)
            fresh.setText(document.immutableCharSequence)
            assertEquals(document.text, tokens(fresh), tokens((myFixture.editor as EditorEx).highlighter))
        }
    }

    private fun tokens(highlighter: EditorHighlighter): List<String> = buildList {
        val iterator = highlighter.createIterator(0)
        while (!iterator.atEnd()) {
            add("${iterator.start}-${iterator.end}:${iterator.tokenType}")
            iterator.advance()
        }
    }
}
