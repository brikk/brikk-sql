package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AlterSet
import dev.brikk.house.sql.ast.AtLocal
import dev.brikk.house.sql.ast.Block
import dev.brikk.house.sql.ast.CaseStatement
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataTypeParam
import dev.brikk.house.sql.ast.CurrentCatalog
import dev.brikk.house.sql.ast.CurrentVersion
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.EndStatement
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.FunctionSpecification
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.If
import dev.brikk.house.sql.ast.IfBlock
import dev.brikk.house.sql.ast.Iterate
import dev.brikk.house.sql.ast.JSONExtract
import dev.brikk.house.sql.ast.JSONExtractQuote
import dev.brikk.house.sql.ast.JSONValue
import dev.brikk.house.sql.ast.Leave
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.LoopBlock
import dev.brikk.house.sql.ast.MatchPredicate
import dev.brikk.house.sql.ast.OnCondition
import dev.brikk.house.sql.ast.Properties
import dev.brikk.house.sql.ast.RepeatBlock
import dev.brikk.house.sql.ast.Return
import dev.brikk.house.sql.ast.StabilityProperty
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.UniquePredicate
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.WhileBlock
import dev.brikk.house.sql.ast.TrinoJsonInput
import dev.brikk.house.sql.ast.TrinoJsonConstructor
import dev.brikk.house.sql.ast.TrinoJsonSerialize
import dev.brikk.house.sql.ast.TrinoJsonScalar
import dev.brikk.house.sql.ast.TrinoJsonPathCall
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.JSONArray
import dev.brikk.house.sql.ast.TrinoIntervalQualifier
import dev.brikk.house.sql.ast.TrinoIntervalValue
import dev.brikk.house.sql.ast.TrinoMaterializedViewExecute
import dev.brikk.house.sql.ast.TrinoNearest
import dev.brikk.house.sql.ast.TrinoSetAuthorization
import dev.brikk.house.sql.ast.TrinoCreateSchema
import dev.brikk.house.sql.ast.GrantPrincipal
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.Neg
import dev.brikk.house.sql.ast.Paren
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.NodeFactory
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.buildLogarithm
import dev.brikk.house.sql.parser.fromArgList
import dev.brikk.house.sql.parser.toJsonPath

private val FRACTIONAL_SECONDS_RE = Regex(":\\d+\\.(\\d+)")

/**
 * Port of sqlglot's TrinoParser (reference/sqlglot/sqlglot/parsers/trino.py).
 * Table merges live in [TrinoParserTables]; overridden _parse_* methods below.
 */
// sqlglot: parsers.trino.TrinoParser
open class TrinoParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.TrinoTokenizerTables.CONFIG,
) : PrestoParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.TRINO

    // sqlglot: Trino.SUPPORTS_USER_DEFINED_TYPES = False
    override val supportsUserDefinedTypes: Boolean get() = false

    private var nearestInputDepth = 0

    override val typeLiteralParsers: Map<DType, (Parser, Expression?, Expression) -> Expression?>
        get() = super.typeLiteralParsers + temporalLiteralParsers + decimalLiteralParsers

    private val decimalLiteralParsers = mapOf<DType, (Parser, Expression?, Expression) -> Expression?>(
        DType.DECIMAL to { _, literal, dataType ->
            // DECIMAL '1.20' is decimal(3,2), not CAST('1.20' AS DECIMAL), whose
            // default scale is zero. 484's JSON model makes that lost scale visible.
            val text = literal?.name.orEmpty().removePrefix("+").removePrefix("-")
            if (text.matches(Regex("[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+"))) {
                val scale = text.substringAfter('.', "").length
                val precision = text.count { it.isDigit() }.coerceAtLeast(1)
                dataType.set("expressions", listOf(precision, scale).map {
                    DataTypeParam(args("this" to Literal.number(it)))
                })
            }
            expression(Cast(args("this" to literal, "to" to dataType)))
        },
    )

    private val temporalLiteralParsers = listOf(DType.TIMESTAMP, DType.TIMESTAMPTZ, DType.TIME, DType.TIMETZ)
        .associateWith { { _: Parser, literal: Expression?, dataType: Expression ->
            if (dataType.expressionsArg.isEmpty()) {
                val precision = FRACTIONAL_SECONDS_RE.find(literal?.name.orEmpty())?.groupValues?.get(1)?.length ?: 0
                dataType.set("expressions", listOf(DataTypeParam(args("this" to Literal.number(precision)))))
            }
            expression(Cast(args("this" to literal, "to" to dataType)))
        } }

    // sqlglot: TrinoParser.NO_PAREN_FUNCTIONS
    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = TrinoParserTables.NO_PAREN_FUNCTIONS

    // sqlglot: TrinoParser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = TrinoParserTables.FUNCTIONS

    // sqlglot: TrinoParser.FUNCTION_PARSERS
    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = TrinoParserTables.FUNCTION_PARSERS

    override val functionsWithAliasedArgs: Set<String>
        get() = super.functionsWithAliasedArgs + "ROW"

    override val funcTokens: Set<TokenType>
        get() = super.funcTokens + TokenType.UNIQUE

    override val subqueryPredicates: Map<TokenType, NodeFactory>
        get() = super.subqueryPredicates + mapOf(
            TokenType.UNIQUE to { nodeArgs -> UniquePredicate(nodeArgs) },
        )

    override fun parseUnary(): Expression? {
        if (isMatchPredicateStart()) {
            advance()
            return parseMatchPredicate(null)
        }
        return super.parseUnary()
    }

    override fun parseRange(this_: Expression?): Expression? {
        val operand = super.parseRange(this_)
        if (isMatchPredicateStart()) {
            advance()
            return parseMatchPredicate(operand)
        }
        return operand
    }

    private fun isMatchPredicateStart(): kotlin.Boolean {
        if (!currToken.text.equals("MATCH", ignoreCase = true)) return false

        var lookahead = index + 1
        if (tokens.getOrNull(lookahead)?.tokenType == TokenType.UNIQUE) lookahead++
        if (tokens.getOrNull(lookahead)?.text?.uppercase() in setOf("SIMPLE", "PARTIAL", "FULL")) {
            lookahead++
        }
        if (tokens.getOrNull(lookahead)?.tokenType != TokenType.L_PAREN) return false

        val queryStart = tokens.getOrNull(lookahead + 1)?.tokenType
        return queryStart in selectStartTokens || queryStart == TokenType.TABLE || queryStart == TokenType.VALUES
    }

    override fun parseTypes(
        checkFunc: kotlin.Boolean,
        schema: kotlin.Boolean,
        allowIdentifiers: kotlin.Boolean,
        withCollation: kotlin.Boolean,
    ): Expression? {
        // JSON(x) is a constructor or ordinary function, never a parameterized type.
        if (checkFunc && currToken.tokenType == TokenType.JSON && nextToken.tokenType == TokenType.L_PAREN) return null
        if (currToken.tokenType == TokenType.INTERVAL) {
            val start = index
            advance()
            val qualifier = parseIntervalQualifier()
            if (qualifier != null && hasIntervalPrecision(qualifier)) {
                return expression(DataType(args("this" to Interval(args("unit" to qualifier)))))
            }
            retreat(start)
        }
        if (currToken.text.equals("NUMBER", ignoreCase = true)) {
            advance()
            return expression(dev.brikk.house.sql.ast.DataType(args("this" to dev.brikk.house.sql.ast.DType.NUMBER)))
        }
        return super.parseTypes(checkFunc, schema, allowIdentifiers, withCollation)
    }

    private fun hasIntervalPrecision(qualifier: TrinoIntervalQualifier): kotlin.Boolean =
        qualifier.args["leading_precision"] != null || qualifier.args["fractional_precision"] != null

    private fun parseIntervalPrecision(): Expression? =
        parseNumber() ?: parseIdVar() ?: raiseError("Expected interval precision")

    private fun parseIntervalQualifier(): TrinoIntervalQualifier? {
        val fields = listOf("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND")
        if (currToken.text.uppercase() !in fields) return null
        val field = currToken.text.uppercase()
        advance()
        var leading: Expression? = null
        var fractional: Expression? = null
        if (match(TokenType.L_PAREN)) {
            leading = parseIntervalPrecision()
            if (field == "SECOND" && match(TokenType.COMMA)) fractional = parseIntervalPrecision()
            matchRParen()
        }
        var end: String? = null
        if (matchTextSeq("TO")) {
            if (currToken.text.uppercase() !in fields) raiseError("Expected ending interval field")
            end = currToken.text.uppercase()
            advance()
            if (end == "SECOND" && match(TokenType.L_PAREN)) {
                fractional = parseIntervalPrecision()
                matchRParen()
            }
        }
        return expression(TrinoIntervalQualifier(args(
            "this" to Var(args("this" to field)), "leading_precision" to leading,
            "fractional_precision" to fractional, "end" to end?.let { Var(args("this" to it)) },
        )))
    }

    override fun parseType(parseInterval: kotlin.Boolean, fallbackToIdentifier: kotlin.Boolean): Expression? {
        if (parseInterval && currToken.tokenType == TokenType.INTERVAL) {
            val start = index
            advance()
            val negative = match(TokenType.DASH)
            if (!negative) match(TokenType.PLUS)
            val value = parseString()
            val qualifier = if (value != null) parseIntervalQualifier() else null
            if (qualifier != null && (hasIntervalPrecision(qualifier) || negative)) {
                return parseColumnOps(expression(Interval(args(
                    "this" to if (negative) Neg(args("this" to value)) else value,
                    "unit" to qualifier,
                ))))
            }
            retreat(start)
        }
        return super.parseType(parseInterval, fallbackToIdentifier)
    }

    override fun parsePrimary(): Expression? {
        val primary = super.parsePrimary()
        if (primary is Paren && currToken.text.uppercase() in setOf("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND") &&
            (nextToken.tokenType in setOf(TokenType.L_PAREN, TokenType.ALIAS) || nextToken.text.equals("TO", ignoreCase = true))) {
            return expression(TrinoIntervalValue(args("this" to primary, "qualifier" to parseIntervalQualifier())))
        }
        return primary
    }

    override fun parseLambda(alias: kotlin.Boolean): Expression? {
        val value = super.parseLambda(alias)
        if (!alias && value is Paren && currToken.text.uppercase() in setOf("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND")) {
            return expression(TrinoIntervalValue(args("this" to value, "qualifier" to parseIntervalQualifier())))
        }
        return value
    }

    override fun parseFunction(
        functions: Map<String, (List<Expression?>) -> Expression>?, anonymous: kotlin.Boolean,
        optionalParens: kotlin.Boolean, anyToken: kotlin.Boolean,
    ): Expression? {
        if (!anonymous && prevToken.tokenType != TokenType.DOT && currToken.tokenType == TokenType.JSON &&
            nextToken.tokenType == TokenType.L_PAREN && isJsonConstructor()) {
            advance(2)
            val result = expression(TrinoJsonConstructor(args("this" to parseTrinoJsonInput())))
            matchRParen()
            return result
        }
        return super.parseFunction(functions, anonymous, optionalParens, anyToken)
    }

    private fun isJsonConstructor(): kotlin.Boolean {
        if (tokens.getOrNull(index + 2)?.tokenType in setOf(TokenType.R_PAREN, TokenType.DISTINCT)) return false
        var depth = 0
        for (token in tokens.drop(index + 2)) {
            when (token.tokenType) {
                TokenType.L_PAREN, TokenType.L_BRACKET -> depth++
                TokenType.R_PAREN -> if (depth == 0) return true else depth--
                TokenType.R_BRACKET -> depth--
                TokenType.COMMA -> if (depth == 0) return false
                else -> Unit
            }
        }
        return false
    }

    fun parseTrinoJsonInput(): Expression? {
        val value = parseDisjunction() ?: raiseError("Expected SQL/JSON input")
        return parseTrinoJsonRepresentation(value)
    }

    private fun parseTrinoJsonRepresentation(value: Expression?): Expression? {
        if (value == null) return null
        if (!matchTextSeq("FORMAT", "JSON")) return value
        val encoding = if (matchTextSeq("ENCODING")) {
            if (!matchTexts(setOf("UTF8", "UTF16", "UTF32"))) raiseError("Expected JSON encoding")
            Var(args("this" to prevToken.text.uppercase()))
        } else null
        return expression(TrinoJsonInput(args("this" to value, "encoding" to encoding)))
    }

    fun parseTrinoJsonSerialize(): Expression {
        val value = parseTrinoJsonInput()
        val returning = if (match(TokenType.RETURNING)) {
            parseTrinoJsonRepresentation(parseTypes() ?: raiseError("Expected JSON_SERIALIZE return type"))
        } else null
        val error = parseOnHandling("ERROR", "NULL", "ERROR")
        return expression(TrinoJsonSerialize(args("this" to value, "returning" to returning, "on_error" to error)))
    }

    fun parseTrinoJsonArray(): Expression {
        val values = if (currToken.tokenType == TokenType.R_PAREN || currToken.tokenType == TokenType.RETURNING ||
            currToken.text.equals("ABSENT", ignoreCase = true) ||
            (currToken.tokenType == TokenType.NULL && nextToken.tokenType == TokenType.ON)) emptyList()
        else parseCsv { parseTrinoJsonInput() }
        val nullHandling = parseOnHandling("NULL", "NULL", "ABSENT")
        val returning = if (match(TokenType.RETURNING)) parseTrinoJsonRepresentation(parseTypes()) else null
        // Preserve the earlier AST shape for the ordinary function-shaped form.
        if (nullHandling == null && returning == null && values.none { it is TrinoJsonInput }) {
            return expression(Anonymous(args("this" to "JSON_ARRAY", "expressions" to values)))
        }
        return expression(JSONArray(args("expressions" to values, "null_handling" to nullHandling, "return_type" to returning)))
    }

    private fun parseTrinoJsonPathCall(kind: String): TrinoJsonPathCall {
        val input = parseTrinoJsonInput()
        if (!match(TokenType.COMMA)) raiseError("Expected SQL/JSON path")
        val path = parseBitwise() ?: raiseError("Expected SQL/JSON path")
        val pathName = if (match(TokenType.ALIAS)) parseIdVar() else null
        val passing = if (matchTextSeq("PASSING")) parseCsv {
            val value = parseTrinoJsonInput()
            if (!match(TokenType.ALIAS)) raiseError("Expected AS in SQL/JSON PASSING argument")
            expression(dev.brikk.house.sql.ast.Alias(args("this" to value, "alias" to parseIdVar())))
        } else emptyList()
        return expression(TrinoJsonPathCall(args(
            "this" to input, "kind" to kind, "path" to path, "path_name" to pathName, "passing" to passing,
        )))
    }

    fun parseTrinoJsonExists(): Expression {
        val invocation = parseTrinoJsonPathCall("JSON_EXISTS")
        invocation.set("on_condition", parseOnHandling("ERROR", "TRUE", "FALSE", "UNKNOWN", "ERROR"))
        if (invocation.args["on_condition"] == null && invocation.thisArg !is TrinoJsonInput &&
            invocation.args["path_name"] == null && invocation.expressionsArg.isEmpty() &&
            (invocation.args["passing"] as? List<*>).isNullOrEmpty()) {
            return expression(Anonymous(args("this" to "JSON_EXISTS", "expressions" to listOf(invocation.thisArg, invocation.args["path"]))))
        }
        return invocation
    }

    override fun parseAlter(): Expression {
        val start = index
        val kind = when {
            matchTextSeq("MATERIALIZED", "VIEW") -> "MATERIALIZED VIEW"
            matchTexts(setOf("TABLE", "VIEW", "SCHEMA")) -> prevToken.text.uppercase()
            else -> null
        }
        if (kind != null) {
            val target = parseTableParts(schema = true)
            if (matchTextSeq("SET", "AUTHORIZATION")) {
                return expression(TrinoSetAuthorization(args(
                    "this" to target, "kind" to kind,
                    "principal" to (parseGrantPrincipal() ?: raiseError("Expected authorization principal")),
                )))
            }
        }
        retreat(start)
        if (matchTextSeq("MATERIALIZED", "VIEW")) {
            val view = parseTableParts(schema = true)
            if (matchTextSeq("EXECUTE")) {
                val procedure = parseIdVar() ?: raiseError("Expected materialized view procedure")
                val parentheses = match(TokenType.L_PAREN)
                val arguments = if (parentheses) parseCsv { parseLambda() } else emptyList()
                if (parentheses) matchRParen()
                val where = if (match(TokenType.WHERE)) parseDisjunction() else null
                return expression(TrinoMaterializedViewExecute(args(
                    "this" to view, "procedure" to procedure, "expressions" to arguments,
                    "parentheses" to parentheses, "where" to where,
                )))
            }
        }
        retreat(start)
        return super.parseAlter()
    }

    override fun parseCreate(): Expression {
        val start = index
        if (match(TokenType.SCHEMA)) {
            val exists = parseExists(not_ = true)
            val target = parseTableParts(schema = true)
            if (matchTextSeq("AUTHORIZATION")) {
                val principal = parseGrantPrincipal() ?: raiseError("Expected schema authorization principal")
                val properties = if (match(TokenType.WITH)) parseWrappedCsv({ parseKeyValueProperty() }) else emptyList()
                return expression(TrinoCreateSchema(args(
                    "this" to target, "exists" to exists, "principal" to principal,
                    "properties" to properties,
                )))
            }
        }
        retreat(start)
        return super.parseCreate()
    }

    override fun parseGrantPrincipal(): Expression? {
        val kind = if (matchTexts(setOf("USER", "ROLE"))) prevToken.text.uppercase() else null
        val principal = parseIdVar() ?: return null
        return expression(GrantPrincipal(args("this" to principal, "kind" to kind)))
    }

    override fun parseTable(
        schema: kotlin.Boolean, joins: kotlin.Boolean, aliasTokens: Collection<TokenType>?,
        parseBracket: kotlin.Boolean, isDbReference: kotlin.Boolean, parsePartition: kotlin.Boolean,
        consumePipe: kotlin.Boolean,
    ): Expression? {
        if (!schema && !isDbReference && currToken.text.equals("NEAREST", ignoreCase = true) &&
            nextToken.tokenType == TokenType.L_PAREN && tokens.getOrNull(index + 2)?.tokenType == TokenType.FROM) {
            advance(3)
            nearestInputDepth++
            val relation = try {
                parseTable(joins = true) ?: raiseError("Expected NEAREST relation")
            } finally {
                nearestInputDepth--
            }
            val where = if (match(TokenType.WHERE)) parseDisjunction() else null
            if (!matchTextSeq("MATCH")) raiseError("Expected NEAREST MATCH predicate")
            val match = parseDisjunction() ?: raiseError("Expected NEAREST MATCH predicate")
            matchRParen()
            val nearest = expression(TrinoNearest(args("this" to relation, "where" to where, "match" to match)))
            nearest.set("alias", parseTableAlias(aliasTokens))
            if (joins) for (join in parseJoins()) nearest.append("joins", join)
            return nearest
        }
        return super.parseTable(schema, joins, aliasTokens, parseBracket, isDbReference, parsePartition, consumePipe)
    }

    override fun parseTableAlias(aliasTokens: Collection<TokenType>?): dev.brikk.house.sql.ast.TableAlias? {
        // MATCH terminates the input relation of NEAREST, rather than naming its alias.
        if (nearestInputDepth > 0 && currToken.text.equals("MATCH", ignoreCase = true)) return null
        return super.parseTableAlias(aliasTokens)
    }

    override fun parseAtTimeZone(this_: Expression?): Expression? {
        if (matchTextSeq("AT", "LOCAL")) {
            return parseAtTimeZone(expression(AtLocal(args("this" to this_))))
        }
        return super.parseAtTimeZone(this_)
    }

    fun parseMatchPredicate(operand: Expression?): Expression {
        val unique = match(TokenType.UNIQUE)
        val matchType = if (matchTexts(setOf("SIMPLE", "PARTIAL", "FULL"))) {
            prevToken.text.uppercase()
        } else {
            null
        }

        matchLParen()
        val query = parseSelect() ?: raiseError("Expected query in MATCH predicate")
        matchRParen()

        return expression(
            MatchPredicate(
                args(
                    "this" to operand,
                    "query" to query,
                    "unique" to unique,
                    "match_type" to matchType,
                )
            )
        )
    }

    override fun parseDmlTarget(
        schema: kotlin.Boolean,
        joins: kotlin.Boolean,
        aliasTokens: Collection<TokenType>?,
        parsePartition: kotlin.Boolean,
    ): Expression? {
        // Trino's DML target is a qualified name followed immediately by an optional
        // branch, before INSERT's optional column list or MERGE's optional alias.
        val target = parseTableParts(schema = schema)
        if (target is Table && match(TokenType.PARAMETER)) {
            target.set("branch", parseIdVar() ?: raiseError("Expected branch name after @"))
        }
        return if (schema) parseSchema(this_ = target) else target
    }

    // sqlglot: TrinoParser.JSON_QUERY_OPTIONS
    protected val jsonQueryOptions: Map<String, List<List<String>>> = buildMap {
        for (key in listOf("WITH", "WITHOUT")) {
            put(
                key,
                listOf(
                    listOf("WRAPPER"),
                    listOf("ARRAY", "WRAPPER"),
                    listOf("CONDITIONAL", "WRAPPER"),
                    listOf("CONDITIONAL", "ARRAY", "WRAPPED"),
                    listOf("UNCONDITIONAL", "WRAPPER"),
                    listOf("UNCONDITIONAL", "ARRAY", "WRAPPER"),
                ),
            )
        }
    }

    // sqlglot: TrinoParser._parse_property — NOT DETERMINISTIC as text-seq (same as BigQuery)
    override fun parseProperty(): kotlin.Any? {
        if (matchTextSeq("NOT", "DETERMINISTIC")) {
            return expression(StabilityProperty(args("this" to Literal.string("VOLATILE"))))
        }
        return super.parseProperty()
    }

    // sqlglot: TrinoParser._parse_cte — WITH FUNCTION <name> is an inline SQL UDF
    override fun parseCte(): Expression? {
        if (
            match(TokenType.FUNCTION, advance = false) &&
            nextToken.exists &&
            nextToken.tokenType in idVarTokens
        ) {
            advance()
            return parseFunctionSpecification()
        }
        return super.parseCte()
    }

    // sqlglot: TrinoParser._parse_function_specification
    open fun parseFunctionSpecification(): Expression {
        val this_ = parseUserDefinedFunction(kind = TokenType.FUNCTION)

        val characteristics = mutableListOf<Expression>()
        val properties = mutableListOf<Expression>()

        while (true) {
            if (match(TokenType.WITH)) {
                properties.addAll(parseWrappedCsv({ parseKeyValueProperty() }))
                continue
            }

            val characteristic = parseProperty() ?: break
            when (characteristic) {
                is Expression -> characteristics.add(characteristic)
                is kotlin.collections.List<*> ->
                    characteristics.addAll(characteristic.filterIsInstance<Expression>())
            }
        }

        return expression(
            FunctionSpecification(
                args(
                    "this" to this_,
                    "characteristics" to if (characteristics.isNotEmpty()) {
                        expression(Properties(args("expressions" to characteristics)))
                    } else {
                        null
                    },
                    "properties" to if (properties.isNotEmpty()) {
                        expression(Properties(args("expressions" to properties)))
                    } else {
                        null
                    },
                    "expression" to parseRoutineStatement(),
                )
            )
        )
    }

    // sqlglot: TrinoParser._parse_routine_statements
    open fun parseRoutineStatements(vararg terminators: String): MutableList<Expression> {
        val statements = mutableListOf<Expression>()
        val terminatorSet = terminators.map { it.uppercase() }.toSet()

        while (!matchTexts(terminatorSet)) {
            if (!currToken.exists) {
                if (chunkIndex >= chunks.size) {
                    raiseError("Unexpected end of routine body")
                    break
                }
                advanceChunk()
            } else if (!match(TokenType.SEMICOLON)) {
                val statement = parseRoutineStatement() ?: break
                statements.add(statement)
            }
        }

        return statements
    }

    // sqlglot: TrinoParser._parse_routine_block
    open fun parseRoutineBlock(): Expression {
        match(TokenType.BEGIN)
        val statements = parseRoutineStatements("END")
        statements.add(EndStatement())
        return expression(Block(args("expressions" to statements, "begin" to true)))
    }

    // sqlglot: TrinoParser._parse_routine_if
    open fun parseRoutineIf(): Expression {
        fun parseBranch(): Expression {
            val condition = parseDisjunction()
            matchTextSeq("THEN")
            val true_ = expression(
                Block(args("expressions" to parseRoutineStatements("ELSEIF", "ELSE", "END")))
            )
            return expression(IfBlock(args("this" to condition, "true" to true_)))
        }

        var this_ = parseBranch()
        var tail = this_
        while (prevToken.text.uppercase() == "ELSEIF") {
            val node = parseBranch()
            tail.set("false", node)
            tail = node
        }

        if (prevToken.text.uppercase() == "ELSE") {
            tail.set(
                "false",
                expression(Block(args("expressions" to parseRoutineStatements("END")))),
            )
        }

        matchTextSeq("IF")
        return this_
    }

    // sqlglot: TrinoParser._parse_routine_case
    open fun parseRoutineCase(): Expression {
        val this_ = parseDisjunction()

        fun parseBranch(): Expression {
            val condition = parseDisjunction()
            matchTextSeq("THEN")
            val true_ = expression(
                Block(args("expressions" to parseRoutineStatements("WHEN", "ELSE", "END")))
            )
            return expression(If(args("this" to condition, "true" to true_)))
        }

        val ifs = mutableListOf<Expression>()
        matchTextSeq("WHEN")
        while (prevToken.text.uppercase() == "WHEN") {
            ifs.add(parseBranch())
        }

        var default: Expression? = null
        if (prevToken.text.uppercase() == "ELSE") {
            default = expression(Block(args("expressions" to parseRoutineStatements("END"))))
        }

        matchTextSeq("CASE")
        return expression(
            CaseStatement(args("this" to this_, "ifs" to ifs, "default" to default))
        )
    }

    // sqlglot: TrinoParser._parse_routine_while
    open fun parseRoutineWhile(label: Expression? = null): Expression {
        val condition = parseDisjunction()
        matchTextSeq("DO")
        val body = expression(Block(args("expressions" to parseRoutineStatements("END"))))
        matchTextSeq("WHILE")
        return expression(WhileBlock(args("this" to condition, "body" to body, "label" to label)))
    }

    // sqlglot: TrinoParser._parse_routine_loop
    open fun parseRoutineLoop(label: Expression? = null): Expression {
        val body = expression(Block(args("expressions" to parseRoutineStatements("END"))))
        matchTextSeq("LOOP")
        return expression(LoopBlock(args("body" to body, "label" to label)))
    }

    // sqlglot: TrinoParser._parse_routine_repeat
    open fun parseRoutineRepeat(label: Expression? = null): Expression {
        val body = expression(Block(args("expressions" to parseRoutineStatements("UNTIL"))))
        val until = parseDisjunction()
        matchTextSeq("END", "REPEAT")
        return expression(RepeatBlock(args("body" to body, "until" to until, "label" to label)))
    }

    // sqlglot: TrinoParser._parse_routine_statement
    open fun parseRoutineStatement(): Expression? {
        // Optional `label :` before WHILE/LOOP/REPEAT (colon lookahead first so SET/IF/etc
        // remain valid label names).
        var label: Expression? = null
        if (nextToken.exists && nextToken.tokenType == TokenType.COLON) {
            label = parseIdVar()
            match(TokenType.COLON)
        }

        if (match(TokenType.BEGIN, advance = false)) {
            return parseRoutineBlock()
        }

        if (matchTextSeq("RETURN")) {
            return expression(Return(args("this" to parseDisjunction())))
        }

        if (matchTextSeq("IF")) {
            return parseRoutineIf()
        }

        if (matchTextSeq("CASE")) {
            return parseRoutineCase()
        }

        if (match(TokenType.DECLARE)) {
            return parseDeclare()
        }

        if (match(TokenType.SET)) {
            return parseSet()
        }

        if (matchTextSeq("ITERATE")) {
            return expression(Iterate(args("this" to parseIdVar())))
        }

        if (matchTextSeq("LEAVE")) {
            return expression(Leave(args("this" to parseIdVar())))
        }

        if (matchTextSeq("WHILE")) {
            return parseRoutineWhile(label = label)
        }

        if (matchTextSeq("LOOP")) {
            return parseRoutineLoop(label = label)
        }

        if (matchTextSeq("REPEAT")) {
            return parseRoutineRepeat(label = label)
        }

        raiseError("Expected routine statement")
        return null
    }

    // sqlglot: TrinoParser._parse_json_query_quote
    open fun parseJsonQueryQuote(): Expression? {
        if (!(matchTextSeq("KEEP", "QUOTES") || matchTextSeq("OMIT", "QUOTES"))) {
            return null
        }

        return expression(
            JSONExtractQuote(
                args(
                    "option" to tokens[index - 2].text.uppercase(),
                    "scalar" to matchTextSeq("ON", "SCALAR", "STRING"),
                )
            )
        )
    }

    // sqlglot: TrinoParser._parse_json_query
    open fun parseJsonQuery(): Expression {
        // sqlglot: `self._match(TokenType.COMMA) and self._parse_bitwise()` — absent
        // comma yields False (serde dumps false), matching the Python arg-presence
        val start = index
        val invocation = parseTrinoJsonPathCall("JSON_QUERY")
        if (invocation.thisArg is TrinoJsonInput || invocation.args["path_name"] != null ||
            !(invocation.args["passing"] as? List<*>).isNullOrEmpty() || currToken.tokenType == TokenType.RETURNING) {
            if (match(TokenType.RETURNING)) invocation.set("returning", parseTrinoJsonRepresentation(parseTypes()))
            invocation.set("option", parseVarFromOptions(jsonQueryOptions, raiseUnmatched = false))
            invocation.set("quote", parseJsonQueryQuote())
            invocation.set("on_condition", parseOnCondition())
            return invocation
        }
        retreat(start)
        val this_ = parseBitwise()
        val expr: kotlin.Any? = if (match(TokenType.COMMA)) parseBitwise() else false
        return expression(
            JSONExtract(
                args(
                    "this" to this_,
                    "expression" to expr,
                    "option" to parseVarFromOptions(jsonQueryOptions, raiseUnmatched = false),
                    "json_query" to true,
                    "quote" to parseJsonQueryQuote(),
                    "on_condition" to parseOnCondition(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_json_value (base parser method reached via FUNCTION_PARSERS)
    open fun parseJsonValue(): Expression {
        val start = index
        val invocation = parseTrinoJsonPathCall("JSON_VALUE")
        if (invocation.thisArg is TrinoJsonInput || invocation.args["path_name"] != null ||
            !(invocation.args["passing"] as? List<*>).isNullOrEmpty()) {
            if (match(TokenType.RETURNING)) invocation.set("returning", parseTypes())
            invocation.set("on_condition", parseOnCondition())
            return invocation
        }
        retreat(start)
        val this_ = parseBitwise()
        match(TokenType.COMMA)
        val path = parseBitwise()

        // sqlglot: `self._match(TokenType.RETURNING) and self._parse_type()`
        val returning: kotlin.Any? = if (match(TokenType.RETURNING)) parseTypes() else false

        return expression(
            JSONValue(
                args(
                    "this" to this_,
                    "path" to toJsonPath(path),
                    "returning" to returning,
                    "on_condition" to parseOnCondition(),
                )
            )
        )
    }

    // brikk extension (docs/brikk-extensions.md #8, NOT sqlglot parity): sqlglot leaves
    // `ALTER TABLE ... SET PROPERTIES ...` unparsed (Command passthrough with a warning).
    // Trino's grammar (reference/trino .../SqlBase.g4 `#setTableProperties`,
    // `property : identifier EQ propertyValue`) takes a bare CSV of property assignments
    // whose keys must be identifiers. We parse them into AlterSet so the generator can
    // render grammar-legal property keys (string-literal keys are normalized to quoted
    // identifiers, e.g. 'foo bar' -> "foo bar").
    override fun parseAlterTableSet(): Expression {
        if (matchTextSeq("PROPERTIES")) {
            val alterSet = expression(AlterSet())
            alterSet.set("option", expression(Var(args("this" to "PROPERTIES"))))
            alterSet.set("expressions", parseCsv { parseSetPropertyAssignment() })
            return alterSet
        }
        return super.parseAlterTableSet()
    }

    // brikk extension (docs/brikk-extensions.md #8): one `property` from Trino's grammar.
    // parseAssignment handles both `key = value` and `key = DEFAULT` (DEFAULT parses as a
    // column identifier); a string-literal key is normalized to a quoted identifier,
    // preserving the property name while making the rendering grammar-legal.
    protected open fun parseSetPropertyAssignment(): Expression? {
        val assignment = parseAssignment() ?: return null
        val key = (assignment as? EQ)?.thisArg as? Expression
        if (key != null && key.isString) {
            assignment.set(
                "this",
                expression(
                    Column(
                        args(
                            "this" to expression(
                                Identifier(args("this" to key.args["this"], "quoted" to true))
                            )
                        )
                    )
                ),
            )
        }
        return assignment
    }

    // sqlglot: Parser.ON_CONDITION_TOKENS
    protected val onConditionTokens: Set<String> get() = setOf("ERROR", "NULL", "TRUE", "FALSE", "EMPTY")

    // sqlglot: Parser._parse_on_condition (ON_CONDITION_EMPTY_BEFORE_ERROR=True)
    protected fun parseOnCondition(): Expression? {
        val empty = parseOnHandling("EMPTY")
        val error = parseOnHandling("ERROR")
        val nullHandling = parseOnHandling("NULL")

        if (empty == null && error == null && nullHandling == null) return null

        return expression(
            OnCondition(args("empty" to empty, "error" to error, "null" to nullHandling))
        )
    }

    // sqlglot: Parser._parse_on_handling
    protected fun parseOnHandling(on: String): kotlin.Any? {
        for (value in onConditionTokens) {
            if (matchTextSeq(value, "ON", on)) return "$value ON $on"
        }
        return null
    }
}

/**
 * Merged parser tables for Trino (sqlglot: TrinoParser class-level dict merges over
 * PrestoParser). Kept in an object so the merges happen once.
 */
object TrinoParserTables {

    // sqlglot: TrinoParser.NO_PAREN_FUNCTIONS
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> =
        PrestoParserTables.NO_PAREN_FUNCTIONS + mapOf(
            TokenType.CURRENT_CATALOG to { CurrentCatalog() },
        )

    // sqlglot: TrinoParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(PrestoParserTables.FUNCTIONS)
        put("ROW") { values ->
            Struct(args("expressions" to values, "trino_row_syntax" to true))
        }
        // sqlglot: parser.py FUNCTIONS["CONCAT_WS"] with Trino.CONCAT_WS_COALESCE=True
        put("CONCAT_WS") { a ->
            dev.brikk.house.sql.ast.ConcatWs(
                args("expressions" to a, "safe" to false, "coalesce" to true)
            )
        }
        put("VERSION", fromArgList(listOf(), false) { CurrentVersion(it) })
        put("JSON_SCALAR", fromArgList(listOf("this"), false) { TrinoJsonScalar(it) })
        // sqlglot: parser.build_logarithm with Trino.LOG_BASE_FIRST = True (base order)
        put("LOG", ::buildLogarithm)
    }

    // sqlglot: TrinoParser.FUNCTION_PARSERS
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        PrestoParserTables.FUNCTION_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "TRIM" to { p -> p.parseTrim() },
            "JSON_QUERY" to { p -> (p as TrinoParser).parseJsonQuery() },
            "JSON_VALUE" to { p -> (p as TrinoParser).parseJsonValue() },
            "JSON_SERIALIZE" to { p -> (p as TrinoParser).parseTrinoJsonSerialize() },
            "JSON_ARRAY" to { p -> (p as TrinoParser).parseTrinoJsonArray() },
            "JSON_EXISTS" to { p -> (p as TrinoParser).parseTrinoJsonExists() },
            "LISTAGG" to { p -> (p as PrestoParser).parseStringAgg() },
        )
}
