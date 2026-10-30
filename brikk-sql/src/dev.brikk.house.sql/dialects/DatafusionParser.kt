package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.BitwiseNot
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.AnonymousAggFunc
import dev.brikk.house.sql.ast.AggFunc
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Kwarg
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DateBin
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GenerateSeries
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.fromArgList
import dev.brikk.house.sql.parser.updatePositions
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.metadata.FunctionKind

/**
 * DataFusion parser — brikk-native, NO sqlglot oracle. It uses the BASE [Parser]
 * grammar and records DataFusion surface spelling needed for readable round trips.
 *
 * The polyglot fixture corpus and the curated DataFusion sqllogictest parse subset
 * use the BASE grammar with local lexical and function overrides. The following
 * are already BASE parser behaviors (verified via the fixture/SLT parse gates):
 *  - `::` cast operator and CAST/TRY_CAST
 *  - `arrow_cast(...)` / `arrow_typeof(...)` (parse as anonymous functions)
 *  - QUALIFY, SELECT * EXCEPT (...) and SELECT * EXCLUDE (...)
 *  - LEFT SEMI / LEFT ANTI joins
 *  - aggregate FILTER (WHERE ...)
 *  - the `|>` pipe operator (PIPE_GT -> PipeQuery)
 *  - postgres-style `~*`, `!~`, `!~*` regex operators
 *  - plural interval units, LIMIT/OFFSET in either order, COPY ... TO
 *
 * Delta: sqlparser-rs GenericDialect also accepts the binary `~` regex-match operator
 * (PGRegexMatch), which BASE only knows as unary bitwise-not. Like the Postgres port,
 * [DatafusionDialect.TOKENIZER_CONFIG] remaps `~` to RLIKE (base RANGE_PARSERS then
 * builds exp.RegexpLike) and [unaryParsers] restores prefix `~x` as BitwiseNot.
 *
 * Native reference cases additionally establish variadic map and sort calls, date_bin's
 * origin argument, and nulls-as-largest ordering. DataFusion-specific handling covers
 * one-argument generate_series(stop), source
 * function/type aliases, `::` casts, SELECT ALL, comparison spelling, and explicit null
 * ordering. Arrow-native type modeling and typing metadata remain separate future work.
 */
// brikk: no sqlglot oracle — BASE parser accepts the datafusion fixture + SLT corpus
open class DatafusionParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = DatafusionDialect.TOKENIZER_CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    override val nullOrdering: String get() = "nulls_are_large"
    override val indexOffset: Int get() = 1

    override val preserveOperatorSpelling: Boolean get() = true

    override val preserveSelectAll: Boolean get() = true

    override val preserveExplicitNullOrdering: Boolean get() = true

    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = FUNCTIONS

    protected open val nativeCatalog: FunctionCatalog
        get() = dev.brikk.house.sql.metadata.DATAFUSION_FUNCTION_CATALOG

    /** Preserve complete native calls instead of borrowing another engine's arity/flags. */
    protected open fun nativeFunction(name: String, values: List<Expression>): Expression {
        val upper = name.uppercase()
        val kinds = nativeCatalog.getAll(name).map { it.kind }
        val typedArity = when (upper) {
            in UNARY_FUNCTIONS -> values.size == 1
            "COALESCE", "GREATEST", "LEAST", "CONCAT", "CONCAT_WS" -> values.isNotEmpty()
            "NULLIF", "POW", "POWER", "ATAN2" -> values.size == 2
            "LOG", "ROUND", "TRUNC" -> values.size in 1..2
            "SUBSTR", "SUBSTRING", "LAG", "LEAD" -> values.size in 1..3
            "STRPOS", "NTH_VALUE" -> values.size == 2
            "ROW_NUMBER", "RANK", "DENSE_RANK", "CURRENT_DATE", "RANDOM", "RAND", "PI" -> values.isEmpty()
            "DATE_TRUNC" -> values.size == 2 && values.first() is Literal
            "DATE_BIN" -> values.size in 2..3
            "GENERATE_SERIES" -> values.size in 1..3
            "REGEXP_LIKE" -> values.size in 2..3
            "TO_CHAR" -> values.size == 2
            else -> false
        }
        val multiDistinct = upper == "COUNT" && (values.firstOrNull() as? Distinct)?.expressionsArg.orEmpty().size > 1
        val result = if (typedArity && !multiDistinct && values.none { it is Kwarg }) functions[upper]?.invoke(values) else null
        if (result != null && (FunctionKind.AGGREGATE !in kinds || result is AggFunc)) return result
        val spelling = if (upper in PRESERVED_FUNCTION_NAMES) name else name.lowercase()
        val arguments = args("this" to spelling, "expressions" to values)
        return if (FunctionKind.AGGREGATE in kinds) AnonymousAggFunc(arguments) else Anonymous(arguments)
    }

    override val columnOperators: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?>
        get() = COLUMN_OPERATORS

    override fun parseFunction(
        functions: Map<String, (List<Expression?>) -> Expression>?,
        anonymous: Boolean,
        optionalParens: Boolean,
        anyToken: Boolean,
    ): Expression? {
        val sourceName = currToken.text
        val upper = sourceName.uppercase()
        val quoted = currToken.tokenType == TokenType.IDENTIFIER
        val qualified = prevToken.tokenType == TokenType.DOT
        if (functions == null && nextToken.tokenType == TokenType.L_PAREN && currToken.tokenType in funcTokens &&
            currToken.tokenType !in subqueryPredicates && (quoted || !hasSpecialFunctionSyntax(upper))) {
            val token = currToken
            advance(2)
            val values = parseNativeArguments(alias = upper == "STRUCT")
            if (prevToken.tokenType == TokenType.COMMA) raiseError("Expected function argument after comma")
            if (values.any { it is Order && it.thisArg == null }) raiseError("ORDER BY must follow a function argument, not a comma")
            val result = if (anonymous || qualified || quoted) {
                val name: Any = if (quoted) Identifier(args("this" to sourceName, "quoted" to true)).updatePositions(token) else sourceName
                val arguments = args("this" to name, "expressions" to values)
                val quotedAggregate = quoted && !qualified && nativeCatalog.functions.any {
                    it.kind == FunctionKind.AGGREGATE && (it.name == sourceName || sourceName in it.aliases)
                }
                if (quotedAggregate) AnonymousAggFunc(arguments) else Anonymous(arguments)
            } else nativeFunction(sourceName, values)
            result.meta["datafusion_native_call"] = true
            if (!quoted && !qualified && upper in PRESERVED_FUNCTION_NAMES) result.meta["datafusion_function_name"] = sourceName
            validateExpression(result).updatePositions(token)
            result.addComments(token.comments)
            matchRParen(result)
            return parseWindow(result)
        }
        val result = super.parseFunction(functions, anonymous, optionalParens, anyToken)
        val function = result?.walk()?.firstOrNull { it is dev.brikk.house.sql.ast.Func }
        if (function != null && sourceName.uppercase() in PRESERVED_FUNCTION_NAMES) {
            function.meta["datafusion_function_name"] = sourceName
        }
        return result
    }

    private fun hasSpecialFunctionSyntax(name: String): Boolean {
        if (name in setOf("CAST", "TRY_CAST", "EXTRACT", "ARRAY", "UNNEST")) return true
        val words = when (name) {
            "POSITION" -> setOf("IN")
            "SUBSTRING" -> setOf("FROM", "FOR")
            "TRIM" -> setOf("FROM", "LEADING", "TRAILING", "BOTH")
            "OVERLAY" -> setOf("PLACING")
            "CEIL", "FLOOR" -> setOf("TO")
            else -> return false
        }
        var depth = 0
        for (token in tokens.drop(index + 1)) {
            if (token.tokenType == TokenType.L_PAREN) depth++
            if (token.tokenType == TokenType.R_PAREN && --depth == 0) break
            if (depth == 1 && token.tokenType != TokenType.STRING && token.tokenType != TokenType.IDENTIFIER && token.text.uppercase() in words) return true
        }
        return false
    }

    private fun parseNativeArguments(alias: Boolean): List<Expression> {
        val result = mutableListOf<Expression>()
        if (currToken.tokenType == TokenType.R_PAREN) return result
        do {
            val start = index
            val value = parseLambda(alias) ?: throw dev.brikk.house.sql.parser.ParseError("Expected function argument")
            if (value is Distinct || value is Order && value.thisArg is Distinct) {
                for (i in start + 1 until index) {
                    if (tokens[i].tokenType == TokenType.COMMA && tokens[i - 1].tokenType == TokenType.COMMA)
                        raiseError("Expected function argument between commas", tokens[i])
                }
            }
            result.add(value)
            if (!match(TokenType.COMMA)) break
            if (currToken.tokenType in setOf(TokenType.COMMA, TokenType.R_PAREN)) raiseError("Expected function argument after comma")
        } while (currToken.exists)
        return result
    }

    override fun parseTypes(
        checkFunc: Boolean,
        schema: Boolean,
        allowIdentifiers: Boolean,
        withCollation: Boolean,
    ): Expression? {
        val sourceName = currToken.text
        val result = super.parseTypes(checkFunc, schema, allowIdentifiers, withCollation)
        if (result is DataType && sourceName.uppercase() in PRESERVED_TYPE_NAMES) {
            result.meta["datafusion_type_name"] = sourceName
        }
        return result
    }

    // brikk: `~` is remapped from TILDA to RLIKE (binary regex match, like Postgres),
    // so prefix `~x` (bitwise not) must be restored here.
    override val unaryParsers: Map<TokenType, (Parser) -> Expression?>
        get() = UNARY_PARSERS

    companion object {
        private val UNARY_FUNCTIONS = setOf(
            "ABS", "ACOS", "ACOSH", "ASIN", "ASINH", "ATAN", "ATANH", "CBRT", "CEIL", "COS", "COSH",
            "COT", "DEGREES", "EXP", "FACTORIAL", "FLOOR", "LN", "LOG2", "LOG10", "RADIANS", "SIN",
            "SINH", "SQRT", "TAN", "TANH", "AVG", "SUM", "MIN", "MAX", "COUNT", "ARRAY_AGG",
            "BOOL_AND", "BOOL_OR", "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "LOWER", "UPPER", "NTILE",
            "FIRST_VALUE", "LAST_VALUE",
        )
        private val PRESERVED_FUNCTION_NAMES = setOf(
            "ARRAY_HAS", "BOOL_AND", "BOOL_OR", "CHAR_LENGTH", "CURRENT_DATE",
            "DATE_TRUNC", "DENSE_RANK", "FIRST_VALUE", "LAG", "LAST_VALUE", "LEAD",
            "LENGTH", "LOG2", "LOG10", "NTH_VALUE", "NTILE", "RANDOM", "RANK",
            "ROW_NUMBER", "STRPOS", "SUBSTR",
        )

        private val PRESERVED_TYPE_NAMES = setOf("BYTEA", "DECIMAL", "INT", "INTEGER", "NUMERIC", "VARCHAR")

        private val FUNCTIONS = BaseParserTables.FUNCTIONS + mapOf(
            // The shared Map and ArraySort shapes model different argument contracts.
            // Preserve all native arguments until an Arrow-specific model is introduced.
            "MAP" to { values: List<Expression?> -> Anonymous(args("this" to "map", "expressions" to values)) },
            "ARRAY_SORT" to { values: List<Expression?> -> Anonymous(args("this" to "array_sort", "expressions" to values)) },
            "DATE_BIN" to { values: List<Expression?> ->
                if (values.size in 2..3) DateBin(args("this" to values[0], "expression" to values[1], "origin" to values.getOrNull(2)))
                    .also { it.meta["datafusion_date_bin"] = true }
                else Anonymous(args("this" to "date_bin", "expressions" to values))
            },
            "GENERATE_SERIES" to { values: List<Expression?> ->
                val args = if (values.size == 1) listOf(Literal.number("0")) + values else values
                fromArgList(listOf("start", "end", "step", "is_end_exclusive"), false) {
                    GenerateSeries(it)
                }(args).also {
                    if (values.size == 1) it.meta["datafusion_omitted_series_start"] = true
                }
            },
        )

        private val COLUMN_OPERATORS = BaseParserTables.COLUMN_OPERATORS +
            (TokenType.DCOLON to { p: Parser, this_: Expression?, to: Expression? ->
                p.buildCast(strict = p.strictCast, this_ = this_, to = to).also {
                    if (it is Cast) it.meta["datafusion_cast_style"] = "dcolon"
                }
            })

        private val UNARY_PARSERS: Map<TokenType, (Parser) -> Expression?> =
            BaseParserTables.UNARY_PARSERS + mapOf<TokenType, (Parser) -> Expression?>(
                TokenType.RLIKE to { p ->
                    p.expression(BitwiseNot(args("this" to p.parseUnary())))
                },
            )
    }
}
