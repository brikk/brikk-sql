package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.DorisDialect
import dev.brikk.house.sql.parser.DorisTokenizerTables
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * brikk extension #53 (docs/brikk-extensions.md): the Doris tokenizer follows the native
 * DorisLexer.g4 (apache/doris 3226e0c) where sqlglot's generated tables differ.
 */
class DorisNativeLexicalRulesTest {

    private fun doris(sql: String): List<Token> = Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenize(sql)

    private fun kinds(sql: String): List<Pair<TokenType, String>> = doris(sql).map { it.tokenType to it.text }

    private fun sqlglot(sql: String): List<Pair<TokenType, String>> =
        Tokenizer(DorisTokenizerTables.CONFIG).tokenize(sql).map { it.tokenType to it.text }

    @Test
    fun untaggedDollarStringIsOneRawLiteral() {
        val sql = "SELECT \$\$a; :p |> 'b\\n\$c\$\$, 1"
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.HEREDOC_STRING to "a; :p |> 'b\\n\$c",
                TokenType.COMMA to ",",
                TokenType.NUMBER to "1",
            ),
            kinds(sql),
        )
        assertFailsWith<TokenError> { sqlglot(sql) }
        assertEquals(listOf(TokenType.SELECT to "SELECT", TokenType.HEREDOC_STRING to ""), kinds("SELECT \$\$\$\$"))
    }

    @Test
    fun dollarStringPositionsSpanLines() {
        val tokens = doris("SELECT \$\$a\nb\$\$ x")
        assertEquals(7 to 13, tokens[1].start to tokens[1].end)
        assertEquals(2, tokens[2].lineStart)
        assertEquals("x", tokens[2].text)
    }

    @Test
    fun taggedDollarFormsAndLongerIdentifiersAreNotLiterals() {
        for (sql in listOf("SELECT \$tag\$a\$tag\$", "SELECT \$\$a\$\$b", "SELECT \$\$abc")) {
            assertEquals(sqlglot(sql), kinds(sql), sql)
        }
        assertEquals(TokenType.VAR to "\$\$a\$\$b", kinds("SELECT \$\$a\$\$b")[1])
    }

    @Test
    fun unclosedDollarStringIsAnEditorFailure() {
        val sql = "SELECT 1, \$\$open; SELECT 2"
        assertEquals(TokenType.VAR to "\$\$open", kinds(sql)[3])
        val editor = Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenizeForEditor(sql)
        assertEquals(sql.indexOf("\$\$"), editor.failureOffset)
        assertEquals(listOf("SELECT", "1", ","), editor.tokens.map { it.text })
    }

    @Test
    fun blockCommentsNest() {
        val sql = "SELECT 1 /* a /* ; */ b */, 2"
        val tokens = doris(sql)
        assertEquals(listOf("SELECT", "1", ",", "2"), tokens.map { it.text })
        assertEquals(listOf(" a /* ; */ b "), tokens[1].comments)
        assertNotEquals(kinds(sql), sqlglot(sql))
        assertEquals(listOf("SELECT", "1"), doris("SELECT /*/* x */*/ 1").map { it.text })
        assertFailsWith<TokenError> { doris("SELECT 1 /* unfinished") }
    }

    // Doris 4.1.4.1 accepts `SELECT 1 /* a /* b */ x y /* c */` but rejects `SELECT 1 x y`.
    @Test
    fun unbalancedNestedCommentClosesAtLastTerminatorExceptInEditor() {
        val sql = "SELECT 1 /* a /* b */ x y /* c */"
        assertEquals(listOf("SELECT", "1"), doris(sql).map { it.text })
        val editor = Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenizeForEditor(sql)
        assertEquals(sql.indexOf("/*"), editor.failureOffset)
        assertEquals(listOf("SELECT", "2"), doris("SELECT /* a /* b */ c */ 2").map { it.text })
    }

    // Each expectation is the native DorisLexer's comment boundary (doris-fe-sql-parser 4.1.4).
    @Test
    fun commentBoundariesFollowTheNativeLexer() {
        val editor = Tokenizer(DorisDialect.TOKENIZER_CONFIG)
        for ((sql, tokens) in listOf(
            "SELECT 1 /*/*/ + 2" to listOf("SELECT", "1", "+", "2"),
            "SELECT 1 /*/*/ 2" to listOf("SELECT", "1", "2"),
            "SELECT 1 /* a /*/ b */ c" to listOf("SELECT", "1", "b", "*", "/", "c"),
            "SELECT /*/ x */ 1" to listOf("SELECT", "1"),
        )) {
            assertEquals(tokens, doris(sql).map { it.text }, sql)
            assertEquals(tokens, editor.tokenizeForEditor(sql).tokens.map { it.text }, sql)
        }
        for ((sql, tokens) in listOf(
            "SELECT /* /* /* /* a */ b */ c */ d" to listOf("SELECT", "d"),
            "SELECT 1 /* a /*/* b */ c */ d" to listOf("SELECT", "1", "d"),
        )) {
            assertEquals(tokens, doris(sql).map { it.text }, sql)
            assertEquals(sql.indexOf("/*"), editor.tokenizeForEditor(sql).failureOffset, sql)
        }
    }

    @Test
    fun hintsNestBlockComments() {
        val tokens = doris("SELECT /*+ SET_VAR(a=1) /* ; :inner */ more */ :p")
        assertEquals(listOf(TokenType.SELECT, TokenType.HINT, TokenType.COLON, TokenType.VAR), tokens.map { it.tokenType })
        assertEquals(listOf(" SET_VAR(a=1) /* ; :inner */ more "), tokens[1].comments)
        val nested = "SELECT /*+ a /* b */ c */ 1"
        assertEquals(listOf("SELECT", "1"), kinds(nested).filter { it.first != TokenType.HINT }.map { it.second })
        assertNotEquals(kinds(nested), sqlglot(nested))
    }

    @Test
    fun dashCommentContinuesAfterBackslashLineFeedOnly() {
        assertEquals(listOf("SELECT", "1", ",", "2"), doris("SELECT 1 -- a\\\n; :hidden\n, 2").map { it.text })
        assertEquals(listOf("SELECT", "1", ";", ":", "visible"), doris("SELECT 1 -- a\\\r\n; :visible").map { it.text })
        assertEquals(listOf("SELECT", "1", ";"), doris("SELECT 1 -- a\\b\n;").map { it.text })
        assertEquals(3, doris("SELECT 1 -- a\\\nb\nFROM t")[2].lineStart)
    }

    @Test
    fun hashAndJinjaMarkersAreNotComments() {
        assertEquals(
            listOf(TokenType.SELECT, TokenType.NUMBER, TokenType.HASH, TokenType.VAR),
            doris("SELECT 1 # note").map { it.tokenType },
        )
        assertEquals(
            listOf(TokenType.SELECT, TokenType.NUMBER, TokenType.L_BRACE, TokenType.HASH, TokenType.VAR,
                TokenType.HASH, TokenType.R_BRACE),
            doris("SELECT 1 {# note #}").map { it.tokenType },
        )
        assertEquals(listOf(TokenType.SELECT, TokenType.NUMBER), sqlglot("SELECT 1 # note").map { it.first })
    }

    @Test
    fun parserReadsDollarStringAsRawString() {
        assertEquals("SELECT 'a''b;c'", dev.brikk.house.sql.dialects.transpile("SELECT \$\$a'b;c\$\$", "doris", "doris"))
    }
}
