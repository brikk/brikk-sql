package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.DorisDialect
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlScriptParameters
import dev.brikk.house.sql.shape.parseParameterOccurrences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Doris IntelliJ plugin's scanner parity corpus (testResources/scanner-corpus/doris-native.json):
 * every input with the native result of its ANTLR-lexer statement splitter and `:name` scanner
 * (doris-fe-sql-parser 4.1.4). Core must equal native except where an accepted convention applies:
 *
 * - core keeps a whitespace-only tail as its own `hasSql = false` range;
 * - from the first statement with an unbalanced bracket, the remainder is one boundary-error
 *   statement (the rule shared with DuckDB/Trino);
 * - colons inside nested STRUCT/MAP field types are not binds (native reports them falsely);
 * - strict discovery refuses scripts and incomplete input, so binds are compared in editor mode,
 *   which reports none at or after the first lexical failure.
 *
 * [KNOWN_DISAGREEMENTS] lists what remains, each with its reason; one that starts agreeing fails.
 */
class DorisScannerCorpusTest {

    @Serializable
    private data class Statement(
        val start: Int,
        val end: Int,
        val hasSql: Boolean,
        val hasPipeOperator: Boolean,
        val boundaryError: Boolean,
    )

    @Serializable
    private data class Bind(val name: String, val start: Int, val end: Int)

    @Serializable
    private data class Native(val statements: List<Statement>, val parameters: List<Bind>)

    @Serializable
    private data class Case(val id: String, val group: String, val input: String, val projectionCount: Int? = null, val native: Native) {
        val sql: String get() = projectionCount?.let { count ->
            input.replace("{projections}", (1..count).joinToString(", ") { "$it AS c$it" })
        } ?: input
    }

    @Serializable
    private data class Corpus(val source: String, val nativeArtifact: String, val grammarRevision: String, val cases: List<Case>)

    private val corpus = Json.decodeFromString(Corpus.serializer(), testResource("scanner-corpus/doris-native.json"))
    private val cases = corpus.cases

    @Test
    fun statementRangesMatchNativeExceptConventions() = compare("statements") { case ->
        expectedStatements(case) to SqlScriptSnapshot.parse(case.sql, "doris").statements.map {
            Statement(it.start, it.end, it.hasSql, it.hasPipe, it.boundaryError != null)
        }
    }

    @Test
    fun editorParametersMatchNativeExceptConventions() = compare("parameters") { case ->
        val editor = SqlScriptParameters.parse(case.sql, "doris")
        val failure = editor.failureOffset
        if (failure != null) {
            val flagged = case.native.statements.singleOrNull { it.boundaryError }
                ?: fail("${case.id}: core lexical failure at $failure, but native flags no boundary error")
            assertTrue(failure in flagged.start..<flagged.end, "${case.id}: failure $failure outside $flagged")
        }
        val falseTypeBinds = NATIVE_FALSE_TYPE_BINDS[case.id].orEmpty()
        val expected = case.native.parameters.filter { (failure == null || it.start < failure) && it.name !in falseTypeBinds }
        expected to editor.occurrences.map { Bind(checkNotNull(it.name), it.range.start, it.range.end) }
    }

    @Test
    fun strictParametersEqualEditorModeOrRefuse() {
        var discovered = 0
        for (case in cases) {
            val strict = try {
                parseParameterOccurrences(case.sql, "doris").filter { it.style == ParamStyle.NAMED_COLON }
            } catch (_: ShapeError) {
                continue
            } catch (_: ParseError) {
                continue
            } catch (_: TokenError) {
                continue
            }
            discovered++
            assertEquals(SqlScriptParameters.parse(case.sql, "doris").occurrences, strict, case.id)
        }
        assertTrue(discovered >= 40, "only $discovered single complete statements were discovered strictly")
    }

    private fun <T> compare(axis: String, results: (Case) -> Pair<T, T>) {
        val known = KNOWN_DISAGREEMENTS.filterKeys { it.second == axis }.keys.map { it.first }.toSet()
        val unexpected = ArrayList<String>()
        val agreeing = ArrayList<String>()
        for (case in cases) {
            val (expected, actual) = results(case)
            when {
                case.id in known -> if (expected == actual) agreeing += case.id
                expected != actual -> unexpected += "${case.id}: ${case.sql}\n  expected $expected\n  actual   $actual"
            }
        }
        println("Doris scanner corpus $axis vs ${corpus.nativeArtifact}: ${cases.size} cases, ${known.size} known disagreements")
        if (agreeing.isNotEmpty()) fail("known $axis disagreements now agree; remove them: $agreeing")
        if (unexpected.isNotEmpty()) fail("${unexpected.size} $axis disagreements:\n${unexpected.joinToString("\n")}")
    }

    private fun expectedStatements(case: Case): List<Statement> {
        val text = case.sql
        val native = case.native.statements
        val unbalanced = native.indexOfFirst { !bracketsBalance(text.substring(it.start, it.end)) }
        val ranges = if (unbalanced < 0) native else {
            val rest = native.subList(unbalanced, native.size)
            native.take(unbalanced) + Statement(rest.first().start, text.length, true, rest.any { it.hasPipeOperator }, true)
        }
        val tail = ranges.lastOrNull()?.end ?: 0
        return if (tail < text.length && text.substring(tail).isBlank()) {
            ranges + Statement(tail, text.length, false, false, false)
        } else ranges
    }

    private fun bracketsBalance(statement: String): Boolean {
        val open = ArrayList<TokenType>()
        for (token in Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenizeForEditor(statement).tokens) {
            when (token.tokenType) {
                TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> open += token.tokenType
                TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> {
                    val opener = when (token.tokenType) {
                        TokenType.R_PAREN -> TokenType.L_PAREN
                        TokenType.R_BRACKET -> TokenType.L_BRACKET
                        else -> TokenType.L_BRACE
                    }
                    if (open.lastOrNull() != opener) return false
                    open.removeAt(open.lastIndex)
                }
                else -> {}
            }
        }
        return open.isEmpty()
    }

    private companion object {
        /** Native's nested-type probing reports these field-type colons as binds. */
        val NATIVE_FALSE_TYPE_BINDS = mapOf(
            "struct-nested-type" to setOf("INT", "MAP"),
            "unterminated-struct" to setOf("INT"),
        )

        val KNOWN_DISAGREEMENTS = mapOf(
            ("jinja-comment" to "statements") to
                "Brackets spanning `;` keep one statement in core. Native splits there, which leaves `{` " +
                    "unbalanced, so the convention expects a boundary error that core does not report.",
            ("jinja-comment" to "parameters") to
                "Native treats `:hidden` after `{# ;` as a map key of a brace group spanning `;`. Core " +
                    "closes brackets at `;`, where Doris starts a new statement, so `:hidden` is a bind.",
            ("unterminated-nested-only" to "statements") to
                "Core marks every lexically unfinished range as SQL (hasSql), so it is never trivia. " +
                    "Native reports hasSql only for default-channel tokens, and its lexer hides the " +
                    "fallback-closed comment.",
        )
    }
}
