package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.BigqueryGenerator
import dev.brikk.house.sql.dialects.BigqueryParser
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.parser.BigqueryTokenizerTables
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.parseParameterOccurrences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SG29-004, upstream c39005c3 and follow-ups through 2fe25297; no oracle refresh. */
class BigqueryIdentifierEscapingTest {
    private val spellings = listOf(
        "a`b\\c" to "`a\\`b\\\\c`",
        "``" to "`\\`\\``",
        "\\`" to "`\\\\\\``",
        "tail\\" to "`tail\\\\`",
        "\\\\n" to "`\\\\\\\\n`",
        "a\nb\tc\r" to "`a\\nb\\tc\\r`",
        "😀\\`é" to "`😀\\\\\\`é`",
    )

    @Test
    fun generationUsesBigqueryBackslashEscapesWithoutCascading() {
        for ((name, expected) in spellings) {
            val identifier = Identifier(args("this" to name, "quoted" to true))
            val before = Serde.dump(identifier)
            assertEquals(expected, identifier.sql("bigquery"), name)
            assertEquals(expected, BigqueryGenerator().generate(identifier), name)
            assertEquals(before, Serde.dump(identifier))
            assertEquals(expected, Serde.loadExpression(before).sql("bigquery"))
            assertEquals(expected, parseOne("SELECT $expected", "bigquery").expressionsArg
                .filterIsInstance<dev.brikk.house.sql.ast.Expression>().single().sql("bigquery"))
        }
        assertEquals("SELECT `a\\`b\\\\c`", transpile("SELECT \"a`b\\c\"", "postgres", "bigquery"))
    }

    @Test
    fun dialectAndDirectParserDecodeEscapesAndKeepTokenOffsets() {
        for ((name, spelling) in spellings) {
            val sql = "SELECT $spelling"
            val tokens = Dialects.BIGQUERY.tokenize(sql)
            val token = tokens.single { it.tokenType == TokenType.IDENTIFIER }
            assertEquals(name, token.text)
            assertEquals(spelling, sql.substring(token.start, token.end + 1))
            assertEquals(8, token.colStart)
            val directParser = BigqueryParser()
            val ownTokens = Tokenizer(directParser.tokenizerConfig).tokenize(sql)
            assertEquals(tokens.map { it.text }, ownTokens.map { it.text })
            val direct = directParser.parse(ownTokens, sql).single()!!
            val throughDialect = parseOne(sql, "bigquery")
            assertEquals(name, throughDialect.findAll<Identifier>().single().name)
            assertEquals(throughDialect, direct)
        }
        // Frozen generated tables remain the historical oracle, not the runtime overlay.
        assertTrue(BigqueryTokenizerTables.IDENTIFIER_ESCAPES.isEmpty())
    }

    @Test
    fun runtimeOverlayAndSubsequentConfigCopiesPreserveIndependentPolicies() {
        val original = BigqueryTokenizerTables.CONFIG
        val runtime = Dialects.BIGQUERY.tokenizerConfig
        for (config in listOf(runtime, runtime.withKeywords(mapOf("SG29_TEST" to TokenType.SELECT)),
            runtime.withIdentifiers(mapOf('[' to "]")), runtime.withStringEscapes(),
            runtime.withCommentPolicy(), runtime.withScientificNumberBoundaries())) {
            assertEquals(setOf('\\'), config.identifierEscapes)
            assertEquals(original.stringEscapes, config.stringEscapes)
            assertEquals(original.numericEscapes, config.numericEscapes)
            assertEquals(original.byteStringsAreBytes, config.byteStringsAreBytes)
            assertEquals("a`b\\c", Tokenizer(config).tokenize("`a\\`b\\\\c`").single().text)
        }
        assertTrue(original.identifierEscapes.isEmpty())
        assertEquals("`select`", BigqueryGenerator().generate(Identifier(args("this" to "select"))))
        assertEquals("`plain`", BigqueryGenerator(identify = true).generate(Identifier(args("this" to "plain"))))
    }

    @Test
    fun repeatedEscapesRoundTripThroughEveryShortCombination() {
        val parts = listOf("a", "`", "\\", "\n", "\t", "😀")
        for (a in parts) for (b in parts) for (c in parts) {
            val name = a + b + c
            val id = Identifier(args("this" to name, "quoted" to true))
            val text = id.sql("bigquery")
            assertEquals(name, parseOne("SELECT $text", "bigquery").findAll<Identifier>().single().name, text)
        }
    }

    @Test
    fun qualifiedNamesStringsAndParameterSourceSpansStayIndependent() {
        val source = "SELECT \"😀a`b\\c\" AS \"x`y\", :p FROM \"pr`j\".\"ds\\n\".\"t`b\""
        val tree = parseOne(source, "postgres")
        val before = Serde.dump(tree)
        val authoredParameter = parseParameterOccurrences(source, "postgres").single()
        assertEquals(":p", source.substring(authoredParameter.range.start, authoredParameter.range.end))
        for (pretty in listOf(false, true)) {
            val generator = Dialects.BIGQUERY.generator(pretty = pretty, sourceDialect = "postgres").apply { trackSpans = true }
            val output = generator.generate(tree)
            val map = assertNotNull(generator.lastSourceMap)
            val id = tree.findAll<Identifier>().first { it.name == "😀a`b\\c" }
            val emitted = "`😀a\\`b\\\\c`"
            assertTrue(emitted in output, output)
            assertEquals(assertNotNull(SourceMap.sourcePosOf(id)).start,
                assertNotNull(map.sourcePosition(output.indexOf(emitted))).start)
            val emittedParameter = parseParameterOccurrences(output, "bigquery").single()
            assertEquals(authoredParameter.name, emittedParameter.name)
            assertEquals("@p", output.substring(emittedParameter.range.start, emittedParameter.range.end))
            assertEquals(authoredParameter, parseParameterOccurrences(source, "postgres").single())
            assertEquals(before, Serde.dump(tree))
            assertEquals(tree.findAll<Identifier>().map { it.name }.toList(),
                parseOne(output, "bigquery").findAll<Identifier>().map { it.name }.toList())
        }
        val value = "'`\\\n\t"
        val stringSql = Literal.string(value).sql("bigquery")
        assertEquals(value, parseOne("SELECT $stringSql", "bigquery").findAll<Literal>().single().name)
        assertEquals("plain", Identifier(args("this" to "plain")).sql("bigquery"))
    }
}
