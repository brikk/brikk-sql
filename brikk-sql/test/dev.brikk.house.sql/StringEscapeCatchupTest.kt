package dev.brikk.house.sql

import dev.brikk.house.sql.ast.ByteString
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.parser.StringEscapeProfiles
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StringEscapeCatchupTest {
    private fun value(tree: Expression): String = (tree.expressionsArg.single() as Expression).name

    @Test
    fun duckdbAndPostgresNumericEscapesDecodeTheirOwnNativeRules() {
        for ((source, duckdb, postgres) in listOf(
            Triple("SELECT e'\\a\\v\\x41\\101\\541'", "avAAa", "a\u000BAAa"),
            Triple("SELECT e'\\xc3\\xa9 \\360\\237\\230\\200'", "é 😀", "é 😀"),
            Triple("SELECT e'\\uD83D\\uDE00'", "uD83DuDE00", "😀"),
            Triple("SELECT e'\\x4Z\\xzz\\z'", "\u0004Zxzzz", "\u0004Zxzzz"),
            Triple("SELECT e'\\\\n\\\\x41'", "\\n\\x41", "\\n\\x41"),
        )) {
            for ((dialect, expected) in listOf("duckdb" to duckdb, "postgres" to postgres)) {
                val tree = Dialects.forName(dialect).parseOne(source)
                assertTrue(tree.expressionsArg.single() is ByteString, source)
                assertEquals(expected, value(tree), "$dialect: $source")
                val output = tree.sql(dialect)
                assertEquals(expected, value(Dialects.forName(dialect).parseOne(output)), output)
                assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
                assertEquals(expected, value(Dialects.DUCKDB.parseOne(tree.sql("duckdb"))))
            }
        }
    }

    @Test
    fun unicodeScalarSurrogateAndInvalidUtf8FallbacksMatchTheFrozenTokenizer() {
        for ((body, expected) in listOf(
            "\\u0041\\U0001f600" to "A😀", "\\uD83D\\uDE00" to "😀",
            "\\uD800" to "uD800", "\\uDC00" to "uDC00", "\\u12" to "u12",
            "\\U00110000" to "U00110000", "\\xc3\\x41" to "ÃA",
            "\\xf0\\x9f\\x98\\x80" to "😀", "\\xe0\\x80\\x80" to "à\u0080\u0080",
        )) assertEquals(expected, value(Dialects.POSTGRES.parseOne("SELECT e'$body'")), body)
        val replacement = StringEscapeProfiles.POSTGRES.withStringEscapes(loneSurrogateReplacement = "?")
        assertEquals("?", Tokenizer(replacement).tokenize("e'\\uD800'").single().text)
    }

    @Test
    fun clickhouseEmptyEscapesAndFixedWidthHexDoNotBecomeDuckdbEscapes() {
        for ((body, expected, emitted) in listOf(
            Triple("\\N\\e\\x41\\xc3\\xa9\\z", "\u001BAé\\z", "\\eAé\\\\z"),
            Triple("\\x4Z\\xzz", "\\x4Z\\xzz", "\\\\x4Z\\\\xzz"),
            Triple("\\\\n", "\\n", "\\\\n"),
            Triple("\\000", "\u000000", "\\000"),
        )) {
            val tree = Dialects.CLICKHOUSE.parseOne("SELECT '$body'")
            assertEquals(expected, value(tree), body)
            assertEquals("SELECT '$emitted'", tree.sql("clickhouse"))
            assertEquals(expected, value(Dialects.CLICKHOUSE.parseOne(tree.sql("clickhouse"))))
        }
    }

    @Test
    fun numericControlsHaveSafeGeneratedSpellingWithoutEscapingPrintableLetters() {
        val duckdb = StringEscapeProfiles.escapedSequences(StringEscapeProfiles.DUCKDB)
        assertEquals("\\x07", duckdb["\u0007"])
        assertEquals("\\x0b", duckdb["\u000B"])
        assertEquals("\\x7f", duckdb["\u007F"])
        assertEquals(null, duckdb["a"])
        assertEquals(null, duckdb["v"])
        val postgres = StringEscapeProfiles.escapedSequences(StringEscapeProfiles.POSTGRES)
        assertEquals("\\v", postgres["\u000B"])
        val clickhouse = StringEscapeProfiles.escapedSequences(StringEscapeProfiles.CLICKHOUSE)
        assertEquals("\\n", clickhouse["\n"], "do not invert the backslash + literal newline rule")
        assertEquals(null, clickhouse[""])
        for (code in (0..31) + 127) {
            val literal = ByteString(dev.brikk.house.sql.ast.args("this" to code.toChar().toString(), "is_bytes" to false))
            for (dialect in listOf("duckdb", "postgres")) {
                val output = "SELECT ${literal.sql(dialect)}"
                if (code == 0 && dialect == "duckdb") {
                    assertEquals("SELECT CHR(0)", output)
                    assertEquals(output, Dialects.DUCKDB.parseOne(output).sql("duckdb"))
                } else assertEquals(code.toChar().toString(), value(Dialects.forName(dialect).parseOne(output)), output)
            }
        }
        assertEquals("\u00000", value(Dialects.DUCKDB.parseOne("SELECT e'\\0000'")))
    }

    @Test
    fun ordinaryDollarQuotedAndRawStringsKeepBackslashesLiteral() {
        for (dialect in listOf("duckdb", "postgres")) {
            for (source in listOf("SELECT '\\n\\x41'", "SELECT \$\$\\n\\x41\$\$", "SELECT \$tag\$\\n\\x41\$tag\$")) {
                val tree = Dialects.forName(dialect).parseOne(source)
                assertEquals("\\n\\x41", value(tree), source)
                assertEquals("\\n\\x41", value(Dialects.forName(dialect).parseOne(tree.sql(dialect))))
            }
        }
        val raw = Dialects.SPARK.parseOne("SELECT r'\\n\\x41'")
        assertEquals("\\n\\x41", value(raw))
        assertEquals("SELECT '\\n\\x41'", raw.sql("duckdb"))
        assertEquals("\\n\\x41", value(Dialects.DUCKDB.parseOne(raw.sql("duckdb"))))
    }

    @Test
    fun incompleteEscapesReportUnterminatedStringsAndTokenizerCanBeReused() {
        for (dialect in listOf("duckdb", "postgres", "clickhouse")) {
            val tokenizer = Tokenizer(Dialects.forName(dialect).tokenizerConfig)
            val prefix = if (dialect == "clickhouse") "'" else "e'"
            for (body in listOf("abc\\", "\\x", "\\x4", "\\x41", "\\\\", "abc\\'")) {
                val failure = assertFailsWith<TokenError>("$dialect: $body") { tokenizer.tokenize(prefix + body) }
                assertTrue("Missing" in failure.message.orEmpty(), failure.message)
                assertEquals("ok", tokenizer.tokenize("'ok'").single().text)
            }
        }
    }

    @Test
    fun decodedEscapesDoNotMoveUtf16TokenTemplateOrGeneratedSourceRanges() {
        val literal = "e'😀\\xc3\\xa9\\n:n\\x41'"
        val source = "SELECT $literal AS txt, :n::INT AS answer -- :n"
        val fragment = SqlFragment(source, "duckdb")
        val token = Dialects.DUCKDB.tokenize(source).single { it.tokenType == TokenType.BYTE_STRING }
        assertEquals("😀é\n:nA", token.text)
        assertEquals(literal, source.substring(token.start, token.end + 1))
        assertEquals(1, token.line)
        assertEquals(token.end + 1, token.col)
        val node = fragment.ast.findAll<ByteString>().single()
        val position = assertNotNull(SourceMap.sourcePosOf(node))
        assertEquals(literal, source.substring(position.start, position.end + 1))
        val executable = fragment.toSourcePreservingExecutable()
        assertEquals(source, executable.sql)
        assertEquals(1, executable.parameterOccurrences.size)
        val marker = executable.parameterOccurrences.single().range
        assertEquals(":n", source.substring(marker.start, marker.end))
        assertEquals(source.indexOf(":n::"), marker.start)
        val generator = Dialects.DUCKDB.generator()
        generator.trackSpans = true
        val output = generator.generate(fragment.ast)
        val map = assertNotNull(generator.lastSourceMap)
        val emitted = output.indexOf("e'")
        assertEquals(position.start, assertNotNull(map.sourcePosition(emitted)).start)
    }

    @Test
    fun actualEscapedNewlinesAndCrlfKeepBothPositionAnchorsCorrect() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            for (body in listOf(newline, "\\$newline")) {
                val source = "SELECT e'a${body}b' AS txt, target"
                for (dialect in listOf("duckdb", "postgres")) {
                    val tokens = Dialects.forName(dialect).tokenize(source)
                    val literal = tokens.single { it.tokenType == TokenType.BYTE_STRING }
                    assertEquals("a${newline}b", literal.text)
                    assertEquals(1, literal.lineStart)
                    assertEquals(8, literal.colStart)
                    assertEquals(2, literal.line)
                    val target = tokens.single { it.text == "target" }
                    assertEquals(2, target.lineStart)
                    assertEquals(source.indexOf("target"), target.start)
                    val breakAt = maxOf(source.lastIndexOf('\n'), source.lastIndexOf('\r'))
                    assertEquals(source.indexOf("target") - breakAt, target.colStart)
                }
            }
        }
    }

    @Test
    fun nulTextLoweringPreservesSourceAstAndMappingsInsteadOfEmbeddingNulInSql() {
        for (source in listOf("SELECT e'a\\x00b' AS text", "SELECT 'a\u0000b' AS text")) {
            val tree = Dialects.DUCKDB.parseOne(source)
            val original = tree.copy()
            val generator = Dialects.DUCKDB.generator()
            generator.trackSpans = true
            val output = generator.generate(tree)
            assertTrue('\u0000' !in output && "CHR(0)" in output, output)
            val sourcePos = assertNotNull(generator.lastSourceMap).sourcePosition(output.indexOf("CHR(0)"))
            assertEquals(source.indexOf("'a") - if ("e'" in source) 1 else 0, assertNotNull(sourcePos).start)
            assertEquals(original, tree)
            assertEquals(output, Dialects.DUCKDB.parseOne(output).sql("duckdb"))
        }
    }

    @Test
    fun keywordOverlaysKeepNumericPolicyAndNeverMutateTheOriginalConfig() {
        val configured = StringEscapeProfiles.DUCKDB.withKeywords(mapOf("TEST_KEYWORD" to TokenType.SELECT))
        assertEquals(StringEscapeProfiles.DUCKDB.numericEscapes, configured.numericEscapes)
        assertTrue(configured.numericEscapesAreBytes && configured.dropUnknownEscapes)
        assertEquals("é", Tokenizer(configured).tokenize("e'\\xc3\\xa9'").single().text)
        assertTrue(TokenizerConfig.BASE.numericEscapes.isEmpty())
        val custom = TokenizerConfig.BASE.withStringEscapes(numericEscapes = mapOf(
            'x' to TokenizerConfig.NumericEscape(16, 2, 2, 255)), unescapedSequences = mapOf("\\0" to "\u0000"))
        assertEquals("\\x01", StringEscapeProfiles.escapedSequences(custom)["\u0001"])
    }
}
