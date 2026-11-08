package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.optimizer.NullabilityRules
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.PIPE_STAGES_RUN_EVERYWHERE
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.scope.Nullability

/**
 * Port of sqlglot's DuckDB dialect umbrella (reference/sqlglot/sqlglot/dialects/duckdb.py
 * class DuckDB). Tokenizer tables are generated (DuckdbTokenizerTables); the parser and
 * generator subclasses live in DuckdbParser.kt / DuckdbGenerator.kt.
 * The default parser keeps historical SQLGlot regex parity. Use [nativeDuckdbParser],
 * [parseDuckdbNativeOne] or SqlFragment for native DuckDB 2.0 operator execution.
 */
// sqlglot: dialects.duckdb.DuckDB
open class DuckdbDialect: Dialect() {
    // brikk-native PIPE evidence: DuckDB 2.0.0 ran every lowered stage below.
    override val pipeRunnableStages: Set<String> get() = PIPE_STAGES_RUN_EVERYWHERE + setOf(
        "CALL", "DROP", "SET", "RENAME", "PIVOT", "UNPIVOT", "TABLESAMPLE", "UNION", "ASOF JOIN", "NATURAL JOIN", "POSITIONAL JOIN",
    )

    override val name: String get() = "duckdb"
    override val singleStatementTriggers: Boolean get() = true

    override fun diagnosticMessage(sql: String, message: String): String = when {
        mayContainDuckdbExternalResources(sql) -> REDACTED_RESOURCE_DIAGNOSTIC
        mayContainDuckdbRouting(sql) -> REDACTED_ROUTING_DIAGNOSTIC
        mayContainDuckdbExtensionManagement(sql) -> REDACTED_EXTENSION_DIAGNOSTIC
        mayContainDuckdbS3UrlData(sql) || mayContainDuckdbS3UrlData(message) -> REDACTED_S3_DIAGNOSTIC
        else -> message
    }

    override fun tokenize(sql: String): List<dev.brikk.house.sql.parser.Token> = try {
        super.tokenize(sql)
    } catch (error: dev.brikk.house.sql.parser.TokenError) {
        val safe = diagnosticMessage(sql, error.message.orEmpty())
        if (safe == error.message) throw error
        throw dev.brikk.house.sql.parser.TokenError(safe)
    }

    // brikk-native: see Dialect.groupByProjectionAliases.
    override val whereProjectionAliases: Boolean get() = true
    override val lateralColumnAliases: Boolean get() = true

    override val usingColumnOrder: UsingColumnOrder get() = UsingColumnOrder.IN_PLACE
    override val qualifiedStarUsing: QualifiedStarUsing get() = QualifiedStarUsing.RAW

    // sqlglot: DuckDB.EXPRESSION_METADATA (sqlglot/typing/duckdb.py)
    override val expressionMetadata get() = dev.brikk.house.sql.ast.GeneratedTypingMetadata.DUCKDB

    // sqlglot: DuckDB.NORMALIZATION_STRATEGY
    override val normalizationStrategy get() = NormalizationStrategy.CASE_INSENSITIVE

    // brikk-native: see Dialect.deduplicatesDerivedColumnNames.
    override val deduplicatesDerivedColumnNames: Boolean get() = true

    // brikk-native: see the Dialect members of the same names.
    override val variantAccessYieldsVariant: Boolean get() = true
    override val triggerScopes: Boolean get() = true
    override val statefulCtes: Boolean get() = true
    override val nativeStorageContracts: Boolean get() = true
    override val nativeFunctionContracts: Boolean get() = true
    override val copyLayoutOptions: Boolean get() = true
    override val dmlScopes: Boolean get() = true
    override val statementResultRows: Boolean get() = true
    override val parametersDefaultToSessionVariables: Boolean get() = true
    override val parametersFromSourceTokens: Boolean get() = true
    override val nestedCatalogPaths: Boolean get() = true
    override val setOperationsIgnoreNullType: Boolean get() = true
    override val usingColumnTakesJoinSide: Boolean get() = true
    override val lambdaNamesKeepCase: Boolean get() = true
    override val preservesWholeRowReferences: Boolean get() = true
    override val integerDivisionByZeroYieldsNull: Boolean get() = true
    override val unnestDepthOptions: Boolean get() = true
    override val requiresConnectTarget: Boolean get() = true
    override val executesSessionStatements: Boolean get() = true
    override val supportsMergeInsertByName: Boolean get() = true
    override val executesExtensionManagement: Boolean get() = true
    override val executesExternalResources: Boolean get() = true
    override val executesExplainOptions: Boolean get() = true
    override val executesBinaryLiterals: Boolean get() = true
    override val executesRegexOperators: Boolean get() = true
    override val executesRecursiveViews: Boolean get() = true

    // Native catalog spelling, not a user-defined type called NULL. Keep the parity
    // parser/annotator unchanged when native NULL-type analysis was not requested.
    override fun parseNativeType(dtype: String): DataType? =
        if (supportsNullType && dtype.trim() == "\"NULL\"") DataType(args("this" to DType.NULL)) else null

    override fun inferExpressionType(expression: Expression): Expression? = expression.duckdbExpressionType(supportsNullType)

    override fun temporalCastType(expression: Expression): DataType? =
        if (supportsNullType) expression.duckdbTemporalCastType() else null

    override fun functionNullability(expression: Expression, rules: NullabilityRules): Nullability? =
        expression.duckdbFunctionNullability(rules)

    // sqlglot: DuckDB.ASCII_ONLY_NORMALIZATION = True
    override val asciiOnlyNormalization: Boolean get() = true

    // sqlglot #8557: table aliases may be referenced as whole-row struct values.
    override val tablesReferenceableAsColumns: Boolean get() = true

    // Generated from the embedded engine's duckdb_functions() registry
    // (tools/generate_duckdb_functions.py).
    override val functionCatalog: FunctionCatalog get() = DUCKDB_FUNCTION_CATALOG

    override val tokenizerConfig: TokenizerConfig get() = DuckdbParserTables.TOKENIZER_CONFIG

    // sqlglot: DuckDB.TIME_MAPPING is the base (empty) mapping; only
    // INVERSE_TIME_MAPPING carries dialect-specific entries (see DuckdbGenerator).

    override fun parser(errorLevel: ErrorLevel?): Parser =
        DuckdbParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        DuckdbGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)
}
