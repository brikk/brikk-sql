package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.metadata.SPICE_FUNCTION_CATALOG
import dev.brikk.house.sql.parser.*
import kotlin.Boolean
import kotlin.String

class SpiceParser(errorLevel: ErrorLevel? = null, tokenizerConfig: TokenizerConfig = SpiceDialect.TOKENIZER_CONFIG) :
    DatafusionParser(errorLevel, tokenizerConfig) {

    override val nativeCatalog get() = SPICE_FUNCTION_CATALOG
    override val bitwise: Map<TokenType, NodeFactory> =
        (BaseParserTables.BITWISE - TokenType.CARET) + (TokenType.HASH to { a: Args -> BitwiseXor(a) })
    override val exponent: Map<TokenType, NodeFactory> = mapOf(TokenType.CARET to { a: Args ->
        Pow(a).also { it.meta["spice_exponent_operator"] = true }
    })

    override val columnOperators = super.columnOperators + mapOf(
        TokenType.ARROW to { p: Parser, value: Expression?, path: Expression? ->
            p.expression(JSONExtract(args("this" to value, "expression" to path))).also { it.meta["native_only_dialect"] = "spice" }
        },
        TokenType.DARROW to { p: Parser, value: Expression?, path: Expression? ->
            p.expression(JSONExtractScalar(args("this" to value, "expression" to path))).also { it.meta["native_only_dialect"] = "spice" }
        },
        TokenType.PLACEHOLDER to { p: Parser, value: Expression?, path: Expression? ->
            p.expression(JSONBContainsTopKey(args("this" to value, "expression" to path))).also { it.meta["native_only_dialect"] = "spice" }
        },
    )

    override fun parseFunction(
        functions: Map<String, (List<Expression?>) -> Expression>?, anonymous: Boolean,
        optionalParens: Boolean, anyToken: Boolean,
    ): Expression? = super.parseFunction(functions, anonymous, optionalParens, anyToken)?.also { result ->
        // Opaque native calls, especially model/search UDTFs and their scalar stubs,
        // have no proved cross-engine translation. Typed common expressions retain
        // their existing translation paths.
        result.walk().filter { it is Anonymous || it is AnonymousAggFunc }.forEach {
            it.meta["native_only_dialect"] = "spice"
            if (it.name.lowercase() in setOf("vector_search", "text_search", "rrf", "rerank")) {
                it.expressionsArg.filterIsInstance<Expression>().forEach { arg -> arg.meta["spice_relation_call_argument"] = true }
            }
        }
    }

    override fun parseStarOps(): Expression? = super.parseStarOps()?.also { star ->
        if (star is Star && listOf("except_", "replace", "rename", "ilike").any { key ->
                val value = star.args[key]
                value != null && value != false && (value !is List<*> || value.isNotEmpty())
            }) fail("Wildcard modifiers are not supported by the pinned Spice PostgreSQL parser profile")
    }

    override fun parseStatement(): Expression? {
        val head = currToken
        if (tokens.any { it.tokenType == TokenType.UNKNOWN && it.text == "`" }) {
            fail("Backtick identifiers are not supported by Spice's PostgreSQL parser profile")
        }
        val result = when {
            matchTextSeq("EXPLAIN") -> parseSpiceExplain()
            matchTextSeq("PREPARE") -> parseSpicePrepare()
            matchTextSeq("EXECUTE") -> parseSpiceExecute()
            matchTextSeq("SHOW") -> parseSpiceShow()
            matchTextSeq("RESET") -> SpiceReset(args("this" to parseSettingName()))
            else -> super.parseStatement()
        }
        if (result is SpiceExplain || result is SpicePrepare || result is SpiceExecute || result is SpiceShow || result is SpiceReset || result is Command) {
            result.meta["native_only_dialect"] = "spice"
            expression(result, head)
        }
        if (result?.walk()?.any { it.metaOrNull?.get("native_only_dialect") == "spice" || it is ClusterProperty } == true) {
            result.meta["native_only_dialect"] = "spice"
        }
        return result
    }

    private fun fail(message: String): Nothing {
        raiseError(message)
        throw ParseError(message)
    }

    private fun requireWord(word: String) {
        if (!matchTextSeq(word)) fail("Expected $word")
    }

    private fun parseSpicePrepare(): Expression {
        val name = parseIdVar() ?: fail("Expected prepared statement name")
        val typed = currToken.tokenType == TokenType.L_PAREN
        val types = if (typed) parseWrappedCsv({ parseTypes() }) else emptyList()
        if (typed && types.isEmpty()) fail("Expected at least one PREPARE parameter type")
        requireWord("AS")
        val query = parseStatement() ?: fail("Expected prepared statement body")
        if (query !is Query && query !is Values && query !is Insert && query !is Update && query !is Delete && query !is Merge)
            fail("Expected query or DML in PREPARE")
        return SpicePrepare(args("this" to name, "expressions" to types, "expression" to query, "typed" to typed))
    }

    private fun parseSpiceExecute(): Expression {
        val name = parseIdVar() ?: fail("Expected prepared statement name")
        val parenthesized = currToken.tokenType == TokenType.L_PAREN
        val values = if (parenthesized) parseWrappedCsv({ parseAssignment() }) else emptyList()
        return SpiceExecute(args("this" to name, "expressions" to values, "parenthesized" to parenthesized))
    }

    private fun parseSpiceShow(): Expression {
        val full = matchTextSeq("FULL")
        val kind = if (matchTexts(setOf("TABLES", "COLUMNS", "ALL", "FUNCTIONS"))) prevToken.text.uppercase() else "SETTING"
        if (full && kind != "COLUMNS") fail("FULL is only supported for SHOW COLUMNS")
        val target = if (kind == "COLUMNS") {
            if (!matchTextSeq("FROM") && !matchTextSeq("IN")) fail("Expected FROM after SHOW COLUMNS")
            parseTableParts(schema = true) ?: fail("Expected table after SHOW COLUMNS")
        } else if (kind == "SETTING") {
            parseSettingName()
        } else null
        val like = if (kind in setOf("TABLES", "FUNCTIONS") && matchTextSeq("LIKE")) parseString() ?: fail("Expected LIKE pattern") else null
        return SpiceShow(args("this" to kind, "target" to target, "like" to like, "full" to full))
    }

    private fun parseSettingName(): Expression {
        var path: Expression = parseIdVar(anyToken = true) ?: fail("Expected setting name")
        while (match(TokenType.DOT)) path = Dot(args("this" to path,
            "expression" to (parseIdVar(anyToken = true) ?: fail("Expected setting name part"))))
        return path
    }

    private fun parseSpiceExplain(): Expression {
        val list = currToken.tokenType == TokenType.L_PAREN && nextToken.tokenType !in QUERY_HEADS && match(TokenType.L_PAREN)
        val options = mutableListOf<Expression>()
        val seen = mutableSetOf<String>()
        fun option(name: String, implicit: Boolean, value: Expression): Expression {
            if (!seen.add(name)) fail("Duplicate EXPLAIN option $name")
            if (name == "FORMAT" && value.name.lowercase() !in setOf("indent", "tree", "pgjson", "graphviz")) fail("Unsupported EXPLAIN format ${value.name}")
            return PropertyEQ(args("this" to Var(args("this" to name)), "expression" to value)).also {
                if (implicit) it.meta["spice_implicit_option"] = true
            }
        }
        fun booleanValue(): Expression? {
            if (!matchTexts(setOf("TRUE", "FALSE", "ON", "OFF", "1", "0"))) return null
            return Var(args("this" to prevToken.text.uppercase()))
        }
        if (list) {
            if (currToken.tokenType == TokenType.R_PAREN) fail("Expected EXPLAIN option")
            do {
                val name = parseIdVar(anyToken = true)?.name?.uppercase() ?: fail("Expected EXPLAIN option")
                if (name !in EXPLAIN_OPTIONS) fail("Unsupported EXPLAIN option $name")
                val flag = name in BOOLEAN_OPTIONS
                val value = if (flag) booleanValue() else parseString() ?: parseIdVar(anyToken = true)
                if (!flag && value == null) fail("Expected value for EXPLAIN $name")
                options.add(option(name, flag && value == null, value ?: Var(args("this" to "TRUE"))))
            } while (match(TokenType.COMMA))
            matchRParen()
        } else {
            while (matchTexts(setOf("ANALYZE", "VERBOSE", "FORMAT"))) {
                val name = prevToken.text.uppercase()
                val value = if (name == "FORMAT") parseString() ?: parseIdVar(anyToken = true) ?: fail("Expected EXPLAIN format") else Var(args("this" to "TRUE"))
                options.add(option(name, name != "FORMAT", value))
            }
        }
        val query = parseStatement() ?: fail("Expected explained statement")
        if (query !is Query && query !is Values && query !is Insert && query !is Update && query !is Delete && query !is Merge && query !is Create)
            fail("Unsupported explained statement")
        return SpiceExplain(args("this" to query, "expressions" to options, "list" to list))
    }

    companion object {
        private val BOOLEAN_OPTIONS = setOf("ANALYZE", "VERBOSE", "TIMING", "SUMMARY", "COSTS")
        private val EXPLAIN_OPTIONS = BOOLEAN_OPTIONS + setOf("FORMAT", "METRICS", "LEVEL")
        private val QUERY_HEADS = setOf(TokenType.SELECT, TokenType.WITH, TokenType.VALUES, TokenType.TABLE,
            TokenType.L_PAREN, TokenType.INSERT, TokenType.UPDATE, TokenType.DELETE, TokenType.MERGE)
    }
}
