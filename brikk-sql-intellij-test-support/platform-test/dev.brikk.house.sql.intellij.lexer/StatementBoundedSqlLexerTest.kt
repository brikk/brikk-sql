package dev.brikk.house.sql.intellij.lexer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
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
        // The platform lexer reads FORMAT after an INSERT's SELECT as the start of data, to the end of the text.
        val text = "INSERT INTO t\nSELECT * FROM s\nFORMAT TSV\nSETTINGS max_threads = 1;\nSELECT 2;"
        assertTrue(CHouseLexer().lexAll(text).raw().any { text.of(it).contains("SELECT 2") })
        val tokens = bounded().lexAll(text)
        assertTrue(tokens.raw().none { text.of(it).contains("SELECT 2") })
        assertEquals(listOf(text.indexOf(";"), text.lastIndexOf(";")), tokens.semicolons(text))
    }

    fun testADataSemicolonStaysInTheData() {
        val text = "INSERT INTO events FORMAT TSV a;\nb;\n;\nSELECT 2;"
        val tokens = bounded().lexAll(text)
        assertEquals("a;\nb;", text.of(tokens.raw().single()))
        assertEquals(listOf(text.indexOf("\n;") + 1, text.lastIndex), tokens.semicolons(text))
    }

    fun testRawDataKeepsSemicolonsAndQuotesInsideIt() {
        for ((name, data) in listOf("json data tail" to "{\"id\":1,\"label\":\"a; b\"}\n{\"id\":2,\"label\":\"c\"}",
            "tsv with semicolons and quotes" to "a;b\tO'Brien", "csv with quotes then a blank line" to "1,O'Brien\n2,\"x;y\"")) {
            val case = CHOUSE_BOUNDARY_CORPUS.single { it.name == name }
            assertEquals(name, data, case.sql.of(bounded().lexAll(case.sql).raw().single()))
        }
    }

    fun testASemicolonInATrailingCommentStaysInIt() {
        for (text in listOf("SELECT 1 -- trailing ;", "SELECT 1 /* ; */", "SELECT 1; -- c ;")) {
            val tokens = bounded().lexAll(text)
            assertEquals(text, CHouseLexer().lexAll(text).map { text.of(it) }, tokens.map { text.of(it) })
            configureCHouse(text)
            assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
        }
    }

    fun testStatementsAfterRawDataParseOnTheirOwn() {
        for (case in CHOUSE_BOUNDARY_CORPUS.filter { "FORMAT" in it.sql && "INSERT INTO events" in it.sql }) {
            configureCHouse(case.sql)
            val terminators = SqlScriptSnapshot.parse(case.sql, "clickhouse").statements.mapNotNull { it.terminator }.toSet()
            var from = 0
            val expected = case.statements.map { statement ->
                val start = case.sql.indexOf(statement, from)
                from = start + statement.length
                case.sql.substring(start, if (from - 1 in terminators) from - 1 else from).trimEnd()
            }
            assertEquals(case.name, expected, statements().map { it.text })
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

    /**
     * The console's parameter preprocessing restarts a lexer mid-statement with the state it
     * reported there: the restart stays inside that statement, and later statements lex as in a
     * full pass.
     */
    fun testStartingInsideAStatementKeepsItsBounds() {
        val text = "SELECT 0;\nINSERT INTO t FORMAT CSV\n1,O'Brien\n;\nSELECT 2;\nSELECT 3;"
        val full = bounded().lexAll(text)
        val inside = full.single { it.type == SqlTokens.SQL_RAW_INPUT }
        assertEquals(1, inside.state)
        val restarted = bounded().lexAll(text, inside.start, inside.state)
        assertEquals(1, restarted.first().state)
        val next = text.indexOf("SELECT 2")
        assertEquals(full.dropWhile { it.start < next }, restarted.dropWhile { it.start < next })
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
