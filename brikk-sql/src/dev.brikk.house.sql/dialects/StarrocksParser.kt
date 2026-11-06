package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.Bracket
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.ToMap
import dev.brikk.house.sql.ast.Like
import dev.brikk.house.sql.ast.Refresh
import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.ast.Pivot
import dev.brikk.house.sql.ast.PivotAlias
import dev.brikk.house.sql.ast.Tuple
import dev.brikk.house.sql.ast.AggregateKeyProperty
import dev.brikk.house.sql.ast.AggregateTypeColumnConstraint
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.ToDays
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.ast.DateAdd
import dev.brikk.house.sql.ast.DateDiff
import dev.brikk.house.sql.ast.DateSub
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Flatten
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.PartitionByRangeProperty
import dev.brikk.house.sql.ast.PartitionByRangePropertyDynamic
import dev.brikk.house.sql.ast.PartitionedByProperty
import dev.brikk.house.sql.ast.PrimaryKey
import dev.brikk.house.sql.ast.Properties
import dev.brikk.house.sql.ast.Property
import dev.brikk.house.sql.ast.RefreshTriggerProperty
import dev.brikk.house.sql.ast.RegexpLike
import dev.brikk.house.sql.ast.RollupIndex
import dev.brikk.house.sql.ast.RollupProperty
import dev.brikk.house.sql.ast.Schema
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.TableFromRows
import dev.brikk.house.sql.ast.TimestampTrunc
import dev.brikk.house.sql.ast.TimeSlice
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.UniqueKeyProperty
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.NodeFactory
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.buildVarMap

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: dialect.build_date_delta_with_interval (default_unit="DAY")
private fun buildDateDeltaWithIntervalDefaultDay(
    factory: NodeFactory,
): (List<Expression?>) -> Expression = { argsList ->
    if (argsList.size < 2) throw ParseError("INTERVAL expression expected")
    val interval = argsList[1]
    if (interval !is Interval) {
        // default_unit branch: DATE_ADD(x, 7) -> DateAdd(this=x, expression=7, unit='DAY')
        factory(
            args(
                "this" to argsList[0],
                // sqlglot: exp.Literal.string(default_unit), then TimeUnit.__init__ -> Var
                "expression" to interval,
                "unit" to normalizeTimeUnit(Literal.string("DAY")),
            )
        )
    } else {
        factory(
            args(
                "this" to argsList[0],
                "expression" to interval.thisArg,
                // sqlglot: unit_to_str(interval), then TimeUnit.__init__ converts to Var
                "unit" to normalizeTimeUnit(starrocksIntervalUnitToStr(interval)),
            )
        )
    }
}

// sqlglot: dialect.unit_to_str for Interval nodes (default "DAY")
private fun starrocksIntervalUnitToStr(interval: Interval): Expression? {
    val unit = interval.args["unit"] as? Expression ?: return Literal.string("DAY")
    return if (unit is dev.brikk.house.sql.ast.Var || unit is Literal) Literal.string(unit.name) else unit
}

// sqlglot: dialect.build_timestamp_trunc -> TimestampTrunc(this=args[1], unit=args[0])
private fun buildStarrocksTimestampTrunc(argsList: List<Expression?>): Expression =
    TimestampTrunc(
        args(
            "this" to seqGet(argsList, 1),
            "unit" to normalizeTimeUnit(seqGet(argsList, 0)),
        )
    )

// sqlglot: StarRocksParser._build_time_slice (#8555 and 6b5191d9e3 fixup).
private fun buildStarrocksTimeSlice(values: List<Expression?>): Expression {
    if (values.size < 2) throw ParseError("TIME_SLICE requires a datetime and interval")
    val interval = values[1] as? Interval
    val amount = (interval?.thisArg as? Expression) ?: values[1]
    val numeric = (amount as? Literal)?.takeIf { it.isString && it.name.toLongOrNull() != null }
        ?.let { Literal.number(it.name) } ?: amount
    val boundary = values.getOrNull(if (interval == null) 3 else 2)?.name?.uppercase()
    val kind = when (boundary) {
        null -> null
        "FLOOR", "START" -> Literal.string("START")
        "CEIL", "END" -> Literal.string("END")
        else -> throw ParseError("TIME_SLICE boundary must be FLOOR or CEIL")
    }
    return TimeSlice(args("this" to values[0], "expression" to numeric,
        "unit" to normalizeTimeUnit(if (interval != null) starrocksIntervalUnitToStr(interval) else values.getOrNull(2)),
        "kind" to kind))
}

/**
 * Port of sqlglot's StarRocksParser (reference/sqlglot/sqlglot/parsers/starrocks.py
 * class StarRocksParser(MySQLParser)). Table merges live in [StarrocksParserTables];
 * overridden _parse_* methods below.
 */
// sqlglot: parsers.starrocks.StarRocksParser
open class StarrocksParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = StarrocksDialect.TOKENIZER_CONFIG,
) : MysqlParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.STARROCKS

    override val mapKeysAreArbitraryExpressions: Boolean get() = true
    override val identifyPivotStrings: Boolean get() = true
    override val pivotColumnNaming: String get() = "agg_name_if_multiple"

    override fun parseStatement(): Expression? = super.parseStatement()?.also {
        if (it is Command) it.meta["starrocks_opaque_command"] = true
    }

    override val constraintParsers: Map<String,(Parser)->Expression?>
        get() = super.constraintParsers + listOf("SUM","MAX","MIN","REPLACE","REPLACE_IF_NOT_NULL","HLL_UNION").associateWith {
            { p: Parser -> p.expression(AggregateTypeColumnConstraint(args("this" to Var(args("this" to p.prevToken.text.uppercase()))))) }
        }

    override fun parsePivot(): Expression? {
        val pivot = super.parsePivot() as? Pivot ?: return null
        if (pivot.args["unpivot"] == true) return pivot
        pivot.meta["starrocks_native_pivot"] = true
        val names = if (pivot.expressionsArg.size > 1) pivot.expressionsArg.filterIsInstance<Expression>().map { it.alias } else listOf("")
        val fields = (pivot.args["fields"] as? List<*>)?.filterIsInstance<Expression>()?.firstOrNull()?.expressionsArg?.filterIsInstance<Expression>().orEmpty()
        fun static(e: Expression): Boolean = e is Literal || e is dev.brikk.house.sql.ast.Boolean || e is dev.brikk.house.sql.ast.Null ||
            e.isNumber || e is Tuple && e.expressionsArg.filterIsInstance<Expression>().all { static(it) }
        if (names.size > 1 && names.any { it.isEmpty() } || fields.any { it !is PivotAlias && !static(it) }) {
            pivot.set("columns", emptyList<Expression>())
            return pivot
        }
        val columns = fields.flatMap { field ->
            val name = when (field) {
                is PivotAlias -> field.alias
                is Tuple -> "{" + field.expressionsArg.filterIsInstance<Expression>().joinToString(",") { it.sql("starrocks") } + "}"
                else -> field.sql("starrocks")
            }
            names.map { aggregate -> toIdentifier(if (aggregate.isEmpty()) name else "${name}_$aggregate") }
        }
        pivot.set("columns",columns)
        return pivot
    }

    // #8682: wrap the literal itself, not the subscripts following it. Its keys
    // remain expressions, including qualified columns, rather than struct names.
    override fun parseBracket(this_: Expression?): Expression? {
        val literal = this_ is Column && this_.table.isEmpty() && this_.name.equals("MAP", true) &&
            match(TokenType.L_BRACE, advance = false)
        val bracket = super.parseBracket(this_) ?: return null
        if (!literal) return bracket
        var struct = bracket
        while (struct is Bracket) struct = struct.thisArg as? Expression ?: return bracket
        val map = expression(ToMap(args("this" to struct.copy()))).also { it.meta["starrocks_map_literal"] = true }
        if (struct === bracket) return map
        struct.replace(map)
        return bracket
    }

    // brikk-native: SELECT ... INTO OUTFILE ends a query (see parseDorisOutfile).
    override val queryModifierParsers: Map<TokenType, (Parser) -> Pair<String, kotlin.Any?>>
        get() = super.queryModifierParsers + (TokenType.INTO to { p -> (p as StarrocksParser).parseDorisOutfile() })

    override fun parseInto(): Expression? = if (isIntoOutfile()) null else super.parseInto()

    // Structured native MV options; unrelated REFRESH forms keep the whole command.
    override fun parseRefresh(): Expression {
        if (!matchTextSeq("MATERIALIZED","VIEW")) return super.parseRefresh()
        val table = parseTableParts()
        var start: Expression? = null
        var end: Expression? = null
        if (matchTextSeq("PARTITION","START")) {
            start = parseWrapped({ parseString() })
            if (start == null || !matchTextSeq("END")) raiseError("Expected PARTITION START/END strings")
            end = parseWrapped({ parseString() })
            if (end == null) raiseError("Expected partition end string")
        }
        val force = matchTextSeq("FORCE")
        val mode = if (matchTextSeq("WITH")) {
            when {
                matchTextSeq("SYNC","MODE") -> "SYNC"
                matchTextSeq("ASYNC","MODE") -> "ASYNC"
                else -> { raiseError("Expected WITH SYNC MODE or WITH ASYNC MODE"); null }
            }
        } else null
        return expression(Refresh(args("this" to table,"kind" to "MATERIALIZED VIEW",
            "partition_start" to start,"partition_end" to end,"force" to force,"mode" to mode)))
    }

    // sqlglot: StarRocks.INDEX_OFFSET = 1 (mirrored by StarrocksGenerator.dialectIndexOffset)
    override val indexOffset: Int get() = 1

    // sqlglot: StarRocksParser.TABLE_ALIAS_TOKENS = MySQLParser.TABLE_ALIAS_TOKENS - {ANTI, SEMI}
    // StarRocks supports LEFT SEMI JOIN and LEFT ANTI JOIN natively.
    override val tableAliasTokens: Set<TokenType>
        get() = StarrocksParserTables.TABLE_ALIAS_TOKENS

    // sqlglot: StarRocksParser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = StarrocksParserTables.FUNCTIONS

    // sqlglot: StarRocksParser.PROPERTY_PARSERS
    override val propertyParsers: Map<String, (Parser, PropertyKwargs) -> kotlin.Any?>
        get() = StarrocksParserTables.PROPERTY_PARSERS

    // sqlglot: StarRocksParser._parse_rollup_property
    open fun parseRollupProperty(): Expression {
        fun parseRollupIndex(): RollupIndex =
            expression(
                RollupIndex(
                    args(
                        "this" to parseIdVar(),
                        "expressions" to parseWrappedIdVars(),
                        "from_index" to if (matchTextSeq("FROM")) parseIdVar() else null,
                        "properties" to if (matchTextSeq("PROPERTIES")) {
                            expression(Properties(args("expressions" to parseWrappedProperties())))
                        } else {
                            null
                        },
                    )
                )
            )

        return expression(
            RollupProperty(args("expressions" to parseWrappedCsv({ parseRollupIndex() })))
        )
    }

    // sqlglot: StarRocksParser._parse_create — StarRocks' primary key is defined outside
    // the schema, so move it in.
    override fun parseCreate(): Expression {
        val create = super.parseCreate()

        if (create is Create && create.thisArg is Schema) {
            val schema = create.thisArg as Schema
            val props = create.args["properties"] as? Expression
            if (props != null) {
                val primaryKey = props.find(PrimaryKey::class)
                if (primaryKey != null) {
                    schema.append("expressions", primaryKey.pop())
                }
            }
        }

        if (create is Create && (create.find<AggregateKeyProperty>() != null ||
            create.findAll<DataType>().any { it.thisArg == DType.HLLSKETCH })) create.meta["starrocks_native_ddl"] = true
        return create
    }

    // sqlglot: StarRocksParser._parse_unnest — StarRocks defaults the UNNEST table
    // alias/column to "unnest".
    override fun parseUnnest(withAlias: kotlin.Boolean): Expression? {
        val unnest = super.parseUnnest(withAlias) ?: return null

        val alias = unnest.args["alias"] as? TableAlias
        if (alias == null) {
            unnest.set(
                "alias",
                expression(
                    TableAlias(
                        args(
                            "this" to toIdentifier("unnest"),
                            "columns" to if (unnest.expressionsArg.size == 1) mutableListOf(toIdentifier("unnest")) else emptyList<Expression>(),
                        )
                    )
                ),
            )
        } else if ((alias.args["columns"] as? List<*>).orEmpty().isEmpty() && unnest.expressionsArg.size == 1) {
            alias.set("columns", mutableListOf(toIdentifier("unnest")))
        }

        return unnest
    }

    // sqlglot: StarRocksParser._parse_partitioned_by
    override fun parsePartitionedBy(): Expression =
        expression(
            PartitionedByProperty(
                args(
                    "this" to expression(
                        Schema(
                            args(
                                "expressions" to parseWrappedCsv({ parseAssignment() }, optional = true)
                            )
                        )
                    )
                )
            )
        )

    // sqlglot: StarRocksParser._parse_partition_property
    override fun parsePartitionProperty(): kotlin.Any? {
        val expr = super.parsePartitionProperty()

        // sqlglot: `if not expr`
        if (expr == null || (expr is List<*> && expr.isEmpty())) {
            return parsePartitionedBy()
        }

        if (expr is Property) return expr

        matchLParen()

        val createExpressions: List<Expression>? = if (matchTextSeq("START", advance = false)) {
            parseCsv { parsePartitioningGranularityDynamic() }
        } else {
            null
        }

        matchRParen()

        val node = PartitionByRangeProperty(
            args(
                "partition_expressions" to expr,
                "create_expressions" to (createExpressions ?: emptyList<Expression>()),
            )
        )
        // brikk-native: `()`, the empty list of dynamic partitions, is legal (as for Doris); built
        // directly so the required-argument check does not reject it.
        return if (createExpressions.isNullOrEmpty()) node else expression(node)
    }

    // sqlglot: StarRocksParser._parse_partitioning_granularity_dynamic
    open fun parsePartitioningGranularityDynamic(): Expression {
        matchTextSeq("START")
        val start = parseWrapped({ parseString() })
        matchTextSeq("END")
        val end = parseWrapped({ parseString() })
        matchTextSeq("EVERY")
        val every = parseWrapped({ parseInterval() ?: parseNumber() })
        return expression(
            PartitionByRangePropertyDynamic(args("start" to start, "end" to end, "every" to every))
        )
    }

    // sqlglot: StarRocksParser._parse_refresh_property
    //   REFRESH [DEFERRED | IMMEDIATE]
    //           [ASYNC | ASYNC [START (<start_time>)] EVERY (INTERVAL <refresh_interval>) | MANUAL]
    open fun parseRefreshProperty(): Expression {
        // sqlglot: `self._match_texts((...)) and self._prev.text.upper()` (False when absent)
        val method: kotlin.Any =
            if (matchTexts(setOf("DEFERRED", "IMMEDIATE"))) prevToken.text.uppercase() else false
        val kind: kotlin.Any =
            if (matchTexts(setOf("ASYNC", "MANUAL"))) prevToken.text.uppercase() else false
        val starts: kotlin.Any =
            if (matchTextSeq("START")) parseWrapped({ parseString() }) ?: false else false

        val every: Expression?
        val unit: Expression?
        if (matchTextSeq("EVERY")) {
            matchLParen()
            matchTextSeq("INTERVAL")
            every = parseNumber()
            unit = parseVar(anyToken = true)
            matchRParen()
        } else {
            every = null
            unit = null
        }

        return expression(
            RefreshTriggerProperty(
                args(
                    "method" to method,
                    "kind" to kind,
                    "starts" to starts,
                    "every" to every,
                    "unit" to unit,
                )
            )
        )
    }
}

/**
 * Merged parser tables for StarRocks (sqlglot: StarRocksParser class-level dict merges
 * over MySQLParser). Kept in an object so the merges happen once.
 */
object StarrocksParserTables {

    // sqlglot: StarRocksParser.TABLE_ALIAS_TOKENS = MySQLParser.TABLE_ALIAS_TOKENS - {ANTI, SEMI}
    val TABLE_ALIAS_TOKENS: Set<TokenType> =
        MysqlParserTables.TABLE_ALIAS_TOKENS - setOf(TokenType.ANTI, TokenType.SEMI)

    // sqlglot: StarRocksParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(MysqlParserTables.FUNCTIONS)
        put("ADDDATE", buildDateDeltaWithIntervalDefaultDay { a -> DateAdd(a) })
        put("DATE_ADD", buildDateDeltaWithIntervalDefaultDay { a -> DateAdd(a) })
        put("DATE_SUB", buildDateDeltaWithIntervalDefaultDay { a -> DateSub(a) })
        put("SUBDATE", buildDateDeltaWithIntervalDefaultDay { a -> DateSub(a) })
        put("DATE_TRUNC", ::buildStarrocksTimestampTrunc)
        put("TIME_SLICE", ::buildStarrocksTimeSlice)
        put("DATEDIFF",MysqlParserTables.FUNCTIONS.getValue("DATEDIFF"))
        put("TO_DAYS") { a -> ToDays(args("this" to seqGet(a,0))).also { it.meta["starrocks_to_days"] = true } }
        put("DATE_DIFF") { a ->
            DateDiff(
                args(
                    "this" to seqGet(a, 1),
                    "expression" to seqGet(a, 2),
                    "unit" to normalizeTimeUnit(seqGet(a, 0)),
                )
            )
        }
        put("ARRAY_FLATTEN") { a -> Flatten(args("this" to seqGet(a, 0))) }
        // #8645: unlike the base LIKE(pattern,value) function, StarRocks is value first.
        put("LIKE") { a ->
            if (a.size != 2) throw ParseError("StarRocks LIKE() requires exactly two arguments")
            Like(args("this" to a[0], "expression" to a[1]))
        }
        put("REGEXP") { a ->
            RegexpLike(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to seqGet(a, 1),
                    "flag" to seqGet(a, 2),
                    "full_match" to seqGet(a, 3),
                )
            )
        }
        // StarRocks' MAP() is a variadic constructor: MAP(k1, v1, k2, v2, ...)
        put("MAP", ::buildVarMap)
        // TABLE(<tvf>) wraps a table function invocation whose arguments are constants
        put("TABLE") { a -> TableFromRows(args("this" to seqGet(a, 0))) }
    }

    // sqlglot: StarRocksParser.PROPERTY_PARSERS
    val PROPERTY_PARSERS: Map<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?> =
        MysqlParserTables.PROPERTY_PARSERS + mapOf<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>(
            "PROPERTIES" to { p, _ -> p.parseWrappedProperties() },
            "AGGREGATE" to { p, _ -> p.parseCompositeKeyProperty { a -> AggregateKeyProperty(a) } },
            "UNIQUE" to { p, _ -> p.parseCompositeKeyProperty { a -> UniqueKeyProperty(a) } },
            "ROLLUP" to { p, _ -> (p as StarrocksParser).parseRollupProperty() },
            "REFRESH" to { p, _ -> (p as StarrocksParser).parseRefreshProperty() },
        )
}
