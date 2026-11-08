package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GeneratedTypingMetadata
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.toIdentifier
import dev.brikk.house.sql.ast.TypingSpec
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.optimizer.NullabilityRules
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.formatTimeString
import dev.brikk.house.sql.parser.withStrictTimeInverse
import dev.brikk.house.sql.scope.Nullability

/**
 * Port of sqlglot's Dialect (reference/sqlglot/sqlglot/dialects/dialect.py) — the
 * umbrella object bundling a tokenizer config, a Parser subclass and a Generator
 * subclass, plus the dialect-level flags they share.
 *
 * Only the flags the ported dialects actually read are mirrored here (TIME_MAPPING
 * and its inverse); parser-/generator-level flags live as open vals on the Parser
 * and Generator subclasses themselves, matching how the Kotlin port distributes
 * sqlglot's Dialect class attributes.
 */
// sqlglot: dialect.NormalizationStrategy
enum class NormalizationStrategy {
    /** Unquoted identifiers are lowercased. */
    LOWERCASE,

    /** Unquoted identifiers are uppercased. */
    UPPERCASE,

    /** Always case-sensitive, regardless of quotes. */
    CASE_SENSITIVE,

    /** Always case-insensitive (lowercase), regardless of quotes. */
    CASE_INSENSITIVE,

    /** Always case-insensitive (uppercase), regardless of quotes. */
    CASE_INSENSITIVE_UPPERCASE,
}

open class Dialect {

    /** Registry name ("" is the base sqlglot dialect). */
    open val name: String get() = ""

    /**
     * The engine's full built-in function catalog (names/aliases/kinds; overloads once
     * signature extraction lands), when one has been generated for this dialect. Null
     * means "unknown surface" — consumers fall back to the parser's translation
     * registries. See FunctionCatalog docs and vendor/README.md.
     */
    open val functionCatalog: FunctionCatalog? get() = null

    /** Native triggers with one inline DML action, not a procedural BEGIN/END body. */
    open val singleStatementTriggers: Boolean get() = false

    // sqlglot: Dialect.NORMALIZATION_STRATEGY
    open val normalizationStrategy: NormalizationStrategy get() = NormalizationStrategy.LOWERCASE

    // sqlglot: Dialect.ASCII_ONLY_NORMALIZATION — when true, identifier case-folding only
    // touches ASCII A-Z/a-z (non-ASCII letters are left as-is).
    open val asciiOnlyNormalization: Boolean get() = false

    // sqlglot: Dialect.EXPRESSION_METADATA (type inference & validation rules; see
    // GeneratedTypingMetadata — doris shares mysql, trino shares presto)
    open val expressionMetadata: Map<kotlin.reflect.KClass<out Expression>, TypingSpec>
        get() = GeneratedTypingMetadata.BASE

    // sqlglot: Dialect.COERCES_TO (empty on the base dialect; TypeAnnotator falls
    // back to the generated lattice when a dialect defines no overrides)
    open val coercesTo: Map<DType, Set<DType>>? get() = null

    // sqlglot: Dialect.DEFAULT_NULL_TYPE (NULL-typed nodes are rewritten to this at
    // the end of annotation unless the dialect supports a NULL/VOID type)
    open val defaultNullType: DType get() = DType.UNKNOWN

    // sqlglot: Dialect.SUPPORTS_NULL_TYPE (Databricks and Spark >=3 support VOID)
    open val supportsNullType: Boolean get() = false

    // sqlglot: Dialect.PRIORITIZE_NON_LITERAL_TYPES (BigQuery-only)
    open val prioritizeNonLiteralTypes: Boolean get() = false

    // sqlglot: Dialect.QUERY_RESULTS_ARE_STRUCTS (BigQuery-only)
    open val queryResultsAreStructs: Boolean get() = false

    // sqlglot: Dialect.PSEUDOCOLUMNS (Oracle/Redshift/BigQuery/Snowflake only)
    open val pseudocolumns: Set<String> get() = emptySet()

    // sqlglot: Dialect.PREFER_CTE_ALIAS_COLUMN (Snowflake only)
    open val preferCteAliasColumn: Boolean get() = false

    // sqlglot: Dialect.FORCE_EARLY_ALIAS_REF_EXPANSION (BigQuery, Clickhouse)
    open val forceEarlyAliasRefExpansion: Boolean get() = false

    // sqlglot: Dialect.EXPAND_ONLY_GROUP_ALIAS_REF (BigQuery only)
    open val expandOnlyGroupAliasRef: Boolean get() = false

    // sqlglot: Dialect.ANNOTATE_ALL_SCOPES (BigQuery only)
    open val annotateAllScopes: Boolean get() = false

    // sqlglot: Dialect.DISABLES_ALIAS_REF_EXPANSION (Oracle only)
    open val disablesAliasRefExpansion: Boolean get() = false

    // sqlglot: Dialect.SUPPORTS_ALIAS_REFS_IN_JOIN_CONDITIONS (Snowflake only)
    open val supportsAliasRefsInJoinConditions: Boolean get() = false

    // sqlglot: Dialect.PROJECTION_ALIASES_SHADOW_SOURCE_NAMES (BigQuery only)
    open val projectionAliasesShadowSourceNames: Boolean get() = false

    // sqlglot: Dialect.TABLES_REFERENCEABLE_AS_COLUMNS (BigQuery, Postgres)
    open val tablesReferenceableAsColumns: Boolean get() = false

    // sqlglot: Dialect.USING_COLUMN_ORDER (#8536)
    open val usingColumnOrder: UsingColumnOrder get() = UsingColumnOrder.USING_LIST
    open val qualifiedStarUsing: QualifiedStarUsing get() = QualifiedStarUsing.MERGED

    // brikk-native: column names compare case-insensitively even where table names do not (MySQL family).
    internal open val caseInsensitiveColumns: Boolean get() = false

    // brikk-native: which clauses may name a projection by its alias, where input columns come first.
    // GROUP BY: not Presto/Trino or Hive. HAVING: not PostgreSQL or Presto/Trino. WHERE and later
    // items of the same SELECT list (lateral aliases): DuckDB and ClickHouse, Spark 3.4+ for lateral.
    internal open val groupByProjectionAliases: Boolean get() = true
    internal open val havingProjectionAliases: Boolean get() = true
    internal open val whereProjectionAliases: Boolean get() = false
    internal open val lateralColumnAliases: Boolean get() = false

    // brikk-native: a derived table renames repeated output names with `_1`, `_2`, ... suffixes (DuckDB).
    internal open val deduplicatesDerivedColumnNames: Boolean get() = false

    // brikk-native: `a.b` may read field `b` of a ROW or struct column `a` (Trino, DuckDB, BigQuery,
    // ClickHouse tuples, Doris structs); PostgreSQL writes `(a).b` and MySQL has no such columns, so there
    // `a` can only name a relation.
    internal open val dottedFieldAccess: Boolean get() = true

    // brikk-native: a table function's arguments may declare its columns, by a structure string or a
    // query (ClickHouse `file('x', 'CSV', 'a UInt32')`, `values('a UInt8', ...)`, `view(SELECT ...)`).
    internal open val tableFunctionArgumentsDeclareColumns: Boolean get() = false

    // brikk-native: `v.a` and `v['a']` on a VARIANT value are VARIANT, and its field names keep their
    // authored spelling through normalization and qualification (DuckDB).
    internal open val variantAccessYieldsVariant: Boolean get() = false

    // brikk-native: trigger DDL is analyzed as a scope: its inline action, NEW/OLD transition tables and
    // the trigger's own name. Type annotation refuses it (DuckDB).
    internal open val triggerScopes: Boolean get() = false

    // brikk-native: WITH may hold recursive USING KEY state and data-modifying CTEs. Scope analysis and
    // shapes model them; qualification and type annotation refuse to rewrite across them (DuckDB).
    internal open val statefulCtes: Boolean get() = false

    // brikk-native: storage layout/property DDL, snapshot reads and CHECKPOINT are native storage
    // contracts that no pass rewrites (DuckDB).
    internal open val nativeStorageContracts: Boolean get() = false

    // brikk-native: GeoJSON, Parquet reader, regex, SETVAL, path, overlay, JSON and VARIANT functions follow
    // reviewed native contracts that analysis models and rewrites refuse (DuckDB).
    internal open val nativeFunctionContracts: Boolean get() = false

    // brikk-native: COPY takes PARTITION_BY/ORDER_BY layout options that bind against its output (DuckDB).
    internal open val copyLayoutOptions: Boolean get() = false

    // brikk-native: INSERT, UPDATE, DELETE, MERGE and COPY are analyzed as scopes with result rows (DuckDB).
    internal open val dmlScopes: Boolean get() = false

    // brikk-native: extension, external-resource and EXPLAIN statements have modeled result rows (DuckDB).
    internal open val statementResultRows: Boolean get() = false

    // brikk-native: a `$name` parameter left unbound falls back to the session variable of that name (DuckDB).
    internal open val parametersDefaultToSessionVariables: Boolean get() = false

    // brikk-native: scalar parameters are found in the source tokens, not only the parsed tree (DuckDB).
    internal open val parametersFromSourceTokens: Boolean get() = false

    // brikk-native: catalog paths may nest deeper than catalog.schema.table (DuckDB).
    internal open val nestedCatalogPaths: Boolean get() = false

    // brikk-native: a set operation column that is NULL-typed in some branches takes the others' type (DuckDB).
    internal open val setOperationsIgnoreNullType: Boolean get() = false

    // brikk-native: a USING column of a non-FULL join is the preserved side's column, not a COALESCE (DuckDB).
    internal open val usingColumnTakesJoinSide: Boolean get() = false

    // brikk-native: lambda parameter lookup tells spellings apart before case-insensitive column lookup (DuckDB).
    internal open val lambdaNamesKeepCase: Boolean get() = false

    // brikk-native: a column named like a selected source that has no such column is a whole-row reference (DuckDB).
    internal open val preservesWholeRowReferences: Boolean get() = false

    // brikk-native: integer division and modulo by zero yield NULL (DuckDB).
    internal open val integerDivisionByZeroYieldsNull: Boolean get() = false

    // brikk-native: UNNEST's recursive/max_depth options can remove several list levels (DuckDB).
    internal open val unnestDepthOptions: Boolean get() = false

    // brikk-native: the target runs as written, as verified against the engine: CONNECT needs a target
    // (bare CONNECT and CONNECT LOCAL are not implemented), PREPARE/EXECUTE/DEALLOCATE/RESET VARIABLE,
    // MERGE ... INSERT BY NAME, extension and repository management, external resources, EXPLAIN options,
    // BLOB/BIT literals, the session-dependent regex operators and recursive views (DuckDB).
    internal open val requiresConnectTarget: Boolean get() = false
    internal open val executesSessionStatements: Boolean get() = false
    internal open val supportsMergeInsertByName: Boolean get() = false
    internal open val executesExtensionManagement: Boolean get() = false
    internal open val executesExternalResources: Boolean get() = false
    internal open val executesExplainOptions: Boolean get() = false
    internal open val executesBinaryLiterals: Boolean get() = false
    internal open val executesRegexOperators: Boolean get() = false
    internal open val executesRecursiveViews: Boolean get() = false

    // brikk-native: -Tuple aggregate combinators (sumTuple, ...) (ClickHouse).
    internal open val supportsTupleAggregateCombinators: Boolean get() = false

    // brikk-native: a contextual pass proves expression types after the generic node rules leave
    // settings-dependent division and builtin calls to it (ClickHouse).
    internal open val contextualTypeInference: Boolean get() = false

    // brikk-native: a field of a Nullable tuple is nullable (ClickHouse).
    internal open val nullableTupleFields: Boolean get() = false

    // brikk-native: JSON_VALUE without RETURNING is TEXT (PostgreSQL, Trino).
    internal open val jsonValueDefaultsToText: Boolean get() = false

    // brikk-native: JSON_SERIALIZE, JSON_EXISTS/JSON_QUERY and JSON_ARRAY take their type from RETURNING (Trino).
    internal open val jsonReturningTypes: Boolean get() = false

    // brikk-native: unqualified PARSE_TO_VARIANT and TRY_PARSE_TO_VARIANT return VARIANT (Doris).
    internal open val parseToVariantFunctions: Boolean get() = false

    // brikk-native: column slots match ignoring case even where the written names differ only by case,
    // so star renames and schema columns must not collide that way (Doris Scope.findSlotIgnoreCase).
    internal open val columnSlotsIgnoreCase: Boolean get() = false

    // brikk-native: FULL JOIN USING star slots behave natively; keep the star as written (StarRocks).
    internal open val preservesFullUsingStar: Boolean get() = false

    // brikk-native: values carry Arrow types: inferred, rendered and shaped by DatafusionTypes, with
    // Arrow function nullability (DataFusion, Spice).
    internal open val arrowTypes: Boolean get() = false

    // brikk-native: completion compares relation names under the dialect's identifier normalization
    // rather than ignoring case (DataFusion, Spice).
    internal open val relationNamesFollowNormalization: Boolean get() = false

    // brikk-native: a column alias list over a relation of unknown width names only the listed columns,
    // since an appended column has no proven ordinal (DataFusion, Spice).
    internal open val aliasColumnsNeedKnownWidth: Boolean get() = false

    // brikk-native: VALUES columns are column1, column2, ... typed from their rows (DataFusion, Spice).
    internal open val typesValuesColumns: Boolean get() = false

    // brikk-native: `a.b.c` may read nested struct fields of a column of a known relation (DataFusion, Spice).
    internal open val nestedStructFields: Boolean get() = false

    // brikk-native: length() of a LargeUtf8 value is BIGINT (DataFusion, not Spice).
    internal open val largeUtf8LengthIsBigint: Boolean get() = false

    // brikk-native: bundled function libraries (json_get*, AI functions) with modeled types and nullability (Spice).
    internal open val bundledFunctionExtensions: Boolean get() = false

    // brikk-native: built-in table functions (vector_search, text_search, flatten_json, ...) whose columns,
    // types and lineage are modeled by SpiceCalls (Spice).
    internal open val builtinTableFunctions: Boolean get() = false

    /** brikk-native: the type of a catalog type string this dialect spells natively, or null for the parser. */
    internal open fun parseNativeType(dtype: String): DataType? = null

    /** brikk-native: the dialect's own type for [expression], ahead of the shared typing rules; null leaves it to them. */
    internal open fun inferExpressionType(expression: Expression): Expression? = null

    /** brikk-native: a temporal CAST type that replaces an earlier annotation (DuckDB native analysis). */
    internal open fun temporalCastType(expression: Expression): DataType? = null

    /** brikk-native: the dialect's own nullability for a function call, or null to apply the shared rules. */
    internal open fun functionNullability(expression: Expression, rules: NullabilityRules): Nullability? = null

    // sqlglot: Dialect.SUPPORTS_STRUCT_STAR_EXPANSION (BigQuery, RisingWave)
    open val supportsStructStarExpansion: Boolean get() = false

    // sqlglot: Dialect.EXCLUDES_PSEUDOCOLUMNS_FROM_STAR (BigQuery only)
    open val excludesPseudocolumnsFromStar: Boolean get() = false

    // sqlglot: Dialect.REQUIRES_PARENTHESIZED_STRUCT_ACCESS (RisingWave only)
    open val requiresParenthesizedStructAccess: Boolean get() = false

    // sqlglot: Dialect.UNNEST_COLUMN_ONLY (BigQuery only)
    open val unnestColumnOnly: Boolean get() = false

    // sqlglot: Dialect.DEFAULT_FUNCTIONS_COLUMN_NAMES (Postgres overrides)
    open val defaultFunctionsColumnNames: Map<kotlin.reflect.KClass<out Expression>, List<String>>
        get() = emptyMap()

    // sqlglot: Dialect.generate_values_aliases (Clickhouse overrides)
    open fun generateValuesAliases(expression: Expression): List<Identifier> =
        ((expression.expressionsArg.firstOrNull() as? Expression)?.expressionsArg ?: emptyList())
            .mapIndexed { i, _ -> toIdentifier("_col_$i")!! }

    // sqlglot: Dialect.tokenizer_class (tables only; the scanner is shared)
    open val tokenizerConfig: TokenizerConfig get() = TokenizerConfig.BASE

    /**
     * brikk-native: whether a statement separator may sit inside brackets (PostgreSQL rule
     * actions do). When false, [dev.brikk.house.sql.parser.SqlScriptSnapshot] makes such a
     * statement a boundary error rather than guessing where it ends.
     */
    open val semicolonsInBrackets: Boolean get() = true

    /**
     * brikk-native: whether `VALUES [lower, upper)` is a half-open partition range whose `[`
     * closes with `)` (Doris). Editor bracket tracking then accepts that pairing after VALUES.
     */
    open val halfOpenPartitionRanges: Boolean get() = false

    /**
     * brikk-native: the PIPE stage phrases (as [dev.brikk.house.sql.parser.pipeStageKeywords] spells
     * them) that the engine runs as core lowers them, by recorded evidence; null without evidence,
     * when every parsed phrase stands. See [dev.brikk.house.sql.parser.pipeRunnableStageKeywords].
     */
    open val pipeRunnableStages: Set<String>? get() = null

    /** brikk-native: whether `{name:Type}` is a server query parameter (ClickHouse). */
    open val typedQueryParameters: Boolean get() = false

    /** brikk-native: whether `?` after a complete operand is the ternary operator `cond ? a : b` (ClickHouse). */
    open val questionMarkTernary: Boolean get() = false

    /** brikk-native: every word of the tokenizer's keywords (`ORDER BY` gives `ORDER` and `BY`), for editor display. */
    val editorKeywords: Set<String> by lazy {
        tokenizerConfig.keywords
            .filter { (word, type) -> type != TokenType.VAR && KEYWORD_PHRASE.matches(word) }
            .keys.flatMap { it.split(' ') }.toSet()
    }

    /** brikk-native: the keyword spellings the parser reads as data types, e.g. `DOUBLE PRECISION`. */
    val typeNames: Set<String> by lazy {
        val types = parser().typeTokens
        tokenizerConfig.keywords.filter { (word, type) -> type in types && KEYWORD_PHRASE.matches(word) }.keys
    }

    // sqlglot: Dialect.TIME_MAPPING (dialect format specifier -> python strftime)
    open val timeMapping: Map<String, String> get() = emptyMap()

    // sqlglot: Dialect.FORMAT_MAPPING (CAST ... FORMAT specifier -> python strftime;
    // empty = fall back to TIME_MAPPING in _parse_cast)
    open val formatMapping: Map<String, String> get() = emptyMap()

    // sqlglot: Dialect.INVERSE_TIME_MAPPING — auto-inverse of TIME_MAPPING, then
    // _with_strict_time_inverse so %mstrict never leaks / pads correctly on strict dialects.
    val inverseTimeMapping: Map<String, String> by lazy {
        withStrictTimeInverse(timeMapping.entries.associate { (k, v) -> v to k })
    }

    // sqlglot: Dialect.parser
    open fun parser(errorLevel: ErrorLevel? = null): Parser =
        Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    // sqlglot: Dialect.generator
    // sourceDialect: the dialect the AST was parsed from (source-aware generation). null =
    // unknown/native (faithful). See Generator.isCrossDialectFrom.
    open fun generator(pretty: Boolean = false, sourceDialect: String? = null): Generator =
        Generator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)

    // sqlglot: Dialect.tokenize
    open fun tokenize(sql: String): List<Token> = Tokenizer(tokenizerConfig).tokenize(sql)

    /** A diagnostic is not an authorized channel for authored connection credentials. */
    open fun diagnosticMessage(sql: String, message: String): String = message

    // sqlglot: Dialect.parse
    fun parse(sql: String): List<Expression?> = parser().parse(tokenize(sql), sql)

    /** sqlglot: sqlglot.parse_one with this dialect. */
    fun parseOne(sql: String): Expression =
        parse(sql).firstOrNull() ?: throw ParseError("No expression was parsed from '$sql'")

    // sqlglot: Dialect.generate
    fun generate(
        expression: Expression,
        pretty: Boolean = false,
        copy: Boolean = true,
        sourceDialect: String? = null,
    ): String =
        generator(pretty = pretty, sourceDialect = sourceDialect).generate(expression, copy = copy)

    /**
     * sqlglot: Dialect.normalize_identifier — transforms an identifier in a way that
     * resembles how it'd be resolved by this dialect. Mutates in place (copy=False
     * semantics), like the Python original.
     */
    open fun <E : Expression> normalizeIdentifier(expression: E): E {
        if (
            expression is Identifier &&
            normalizationStrategy != NormalizationStrategy.CASE_SENSITIVE &&
            (
                !expression.quoted ||
                    normalizationStrategy == NormalizationStrategy.CASE_INSENSITIVE ||
                    normalizationStrategy == NormalizationStrategy.CASE_INSENSITIVE_UPPERCASE
                )
        ) {
            val name = expression.thisArg as String
            val upper =
                normalizationStrategy == NormalizationStrategy.UPPERCASE ||
                    normalizationStrategy == NormalizationStrategy.CASE_INSENSITIVE_UPPERCASE
            val normalized = when {
                upper && asciiOnlyNormalization -> asciiTranslate(name, toUpper = true)
                upper -> name.uppercase()
                asciiOnlyNormalization -> asciiTranslate(name, toUpper = false)
                else -> name.lowercase()
            }
            expression.set("this", normalized)
        }
        return expression
    }

    // sqlglot: ASCII_LOWER / ASCII_UPPER translation tables (ASCII letters only).
    private fun asciiTranslate(s: String, toUpper: Boolean): String = buildString(s.length) {
        for (c in s) append(
            when {
                toUpper && c in 'a'..'z' -> c - 32
                !toUpper && c in 'A'..'Z' -> c + 32
                else -> c
            }
        )
    }

    /**
     * sqlglot: Dialect.can_quote — whether an identifier can be quoted. [identify] is
     * `true`/`false` or the strings "safe"/"unsafe", matching the Python contract.
     */
    fun canQuote(identifier: Identifier, identify: Any = "safe"): Boolean {
        if (identifier.quoted) return true
        if (identify == false || identify == "") return false
        if (identifier.parent is dev.brikk.house.sql.ast.Func) return false
        if (identify == true) return true

        val text = identifier.thisArg as? String ?: ""
        val isSafe = !caseSensitive(text) && dev.brikk.house.sql.ast.SAFE_IDENTIFIER_RE.matches(text)

        return when (identify) {
            "safe" -> isSafe
            "unsafe" -> !isSafe
            else -> throw IllegalArgumentException("Unexpected argument for identify: '$identify'")
        }
    }

    /**
     * sqlglot: Dialect.quote_identifier — adds quotes to a given expression if it is
     * an identifier (mutates in place).
     */
    fun <E : Expression> quoteIdentifier(expression: E, identify: Boolean = true): E {
        if (expression is Identifier) {
            expression.set("quoted", canQuote(expression, if (identify) true else "unsafe"))
        }
        return expression
    }

    // sqlglot: Dialect.case_sensitive — checks if text contains case sensitive characters.
    fun caseSensitive(text: String): Boolean {
        if (normalizationStrategy == NormalizationStrategy.CASE_INSENSITIVE) return false
        return if (normalizationStrategy == NormalizationStrategy.UPPERCASE) {
            text.any { it.isLowerCase() }
        } else {
            text.any { it.isUpperCase() }
        }
    }

    /**
     * sqlglot: Dialect.format_time — converts a time-format literal in this dialect
     * to its python-strftime equivalent. Non-string expressions pass through.
     */
    fun formatTime(expression: Expression?): Expression? {
        if (expression is Literal && expression.isString) {
            val converted = formatTimeString(expression.thisArg as? String, timeMapping)
            return Literal(args("this" to converted, "is_string" to true))
        }
        return expression
    }
}

private val KEYWORD_PHRASE = Regex("[A-Za-z][A-Za-z0-9_]*( [A-Za-z][A-Za-z0-9_]*)*")

/**
 * Thrown by every public entry point ([Dialects.forName], [transpile], [Expression.sql],
 * [dev.brikk.house.sql.parser.parseOne], ...) when a dialect name is not registered. Unknown
 * names — including non-ported sqlglot dialects such as `snowflake` and typos such as
 * `postgress` — are always rejected, never silently parsed with the base dialect.
 * Subclasses IllegalArgumentException for source compatibility with earlier releases.
 * This is an argument error, not a SQL error, so it deliberately stays outside the BrikkSqlError family.
 */
class UnknownDialectException(val dialectName: String) : IllegalArgumentException(
    "Unknown dialect: '$dialectName'. Registered dialects: ${Dialects.NAMES.joinToString(", ")}"
)

/**
 * sqlglot: Dialect registry (Dialect.__getitem__ / get_or_raise). Unknown names
 * raise [UnknownDialectException]; use [forNameOrNull] for availability checks.
 * This is the single source of truth for dialect names — tokenizer-config lookup
 * ([dev.brikk.house.sql.parser.TokenizerConfigs]) and `parseOne` route through it.
 */
object Dialects {
    val BASE: Dialect = Dialect()
    val MYSQL: Dialect = MysqlDialect()
    val DORIS: Dialect = DorisDialect()
    val STARROCKS: Dialect = StarrocksDialect()
    val PRESTO: Dialect = PrestoDialect()
    val TRINO: Dialect = TrinoDialect()
    val DUCKDB: Dialect = DuckdbDialect()
    val POSTGRES: Dialect = PostgresDialect()
    val CLICKHOUSE: Dialect = ClickhouseDialect()
    val HIVE: Dialect = HiveDialect()
    val SPARK2: Dialect = Spark2Dialect()
    val SPARK: Dialect = SparkDialect()
    val BIGQUERY: Dialect = BigqueryDialect()

    // brikk-native: no sqlglot oracle — see DatafusionDialect.kt.
    val DATAFUSION: Dialect = DatafusionDialect()
    val SPICE: Dialect = SpiceDialect()

    fun forNameOrNull(name: String): Dialect? = when (name.lowercase().trim()) {
        "", "sqlglot" -> BASE
        "mysql" -> MYSQL
        "doris" -> DORIS
        "starrocks" -> STARROCKS
        "presto" -> PRESTO
        "trino" -> TRINO
        "duckdb" -> DUCKDB
        "postgres", "postgresql" -> POSTGRES
        "clickhouse" -> CLICKHOUSE
        "hive" -> HIVE
        "spark2" -> SPARK2
        "spark", "sparksql" -> SPARK
        "bigquery" -> BIGQUERY
        "datafusion", "arrow-datafusion" -> DATAFUSION
        "spice" -> SPICE
        else -> null
    }

    fun forName(name: String): Dialect =
        forNameOrNull(name) ?: throw UnknownDialectException(name)

    /** Every accepted spelling (canonical names first, then aliases), for messages and docs. */
    val NAMES: List<String> = listOf(
        "sqlglot", "mysql", "doris", "starrocks", "presto", "trino", "duckdb", "postgres",
        "clickhouse", "hive", "spark2", "spark", "bigquery", "datafusion", "spice",
        // aliases
        "postgresql", "sparksql", "arrow-datafusion",
    )
}

/**
 * sqlglot: sqlglot.transpile. Parses and generates every non-empty statement in [sql].
 */
fun transpileAll(sql: String, read: String = "", write: String = "", pretty: Boolean = false): List<String> {
    val source = Dialects.forName(read)
    val target = Dialects.forName(write)
    return source.parse(sql).filterNotNull().filterNot { it is dev.brikk.house.sql.ast.Semicolon }.map { expression ->
        target.generate(
            expression,
            pretty = pretty,
            // Tell the target which dialect produced the AST so cross-dialect rewrites fire.
            sourceDialect = read.ifBlank { null },
        )
    }
}

/** Single-statement convenience. Use [transpileAll] for SQL scripts. */
fun transpile(sql: String, read: String = "", write: String = "", pretty: Boolean = false): String {
    val statements = transpileAll(sql, read, write, pretty)
    require(statements.size == 1) {
        "transpile accepts exactly one SQL statement, found ${statements.size}; use transpileAll for scripts"
    }
    return statements.single()
}

/** sqlglot: Expression.sql(dialect=...) convenience. */
fun Expression.sql(dialect: String = "", pretty: Boolean = false): String =
    Dialects.forName(dialect).generate(this, pretty = pretty)
