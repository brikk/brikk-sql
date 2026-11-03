package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.GeneratedTypingMetadata
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.metadata.CLICKHOUSE_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.PIPE_STAGES_RUN_EVERYWHERE
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.StringEscapeProfiles

/**
 * Port of sqlglot's ClickHouse dialect umbrella (reference/sqlglot/sqlglot/dialects/clickhouse.py
 * class ClickHouse). Tokenizer tables are generated (ClickhouseTokenizerTables); the parser
 * and generator subclasses live in ClickhouseParser.kt / ClickhouseGenerator.kt.
 */
// sqlglot: dialects.clickhouse.ClickHouse
open class ClickhouseDialect : Dialect() {
    // brikk-native: see Dialect.tableFunctionArgumentsDeclareColumns.
    override val tableFunctionArgumentsDeclareColumns: Boolean get() = true

    // brikk-native PIPE evidence: a ClickHouse 26.7 server ran the lowered stages (the function
    // catalog has separate 26.5/26.8/26.10 snapshots; that does not relabel the pipe evidence). The
    // lowered CALL and RENAME do not run; core still refuses or blocks PIVOT and UNPIVOT.
    // NATURAL JOIN additionally has pinned 26.8/26.10 original/lowered evidence in batch 4.
    override val pipeRunnableStages: Set<String> get() = PIPE_STAGES_RUN_EVERYWHERE + setOf(
        "DROP", "SET", "TABLESAMPLE", "ALL JOIN", "ANY JOIN", "ASOF JOIN", "ARRAY JOIN", "NATURAL JOIN",
    )

    override val name: String get() = "clickhouse"

    // brikk-native: see Dialect.groupByProjectionAliases.
    override val whereProjectionAliases: Boolean get() = true
    override val lateralColumnAliases: Boolean get() = true

    // sqlglot: ClickHouseParser._parse_query_parameter
    override val typedQueryParameters: Boolean get() = true
    override val questionMarkTernary: Boolean get() = true

    // sqlglot: ClickHouse.FORCE_EARLY_ALIAS_REF_EXPANSION
    override val forceEarlyAliasRefExpansion: Boolean get() = true

    // sqlglot: ClickHouse.generate_values_aliases — CH VALUES may carry an embedded
    // structure string ('person String, place String') naming the columns; otherwise
    // default column aliases are "c1", "c2", ...
    override fun generateValuesAliases(
        expression: dev.brikk.house.sql.ast.Expression,
    ): List<dev.brikk.house.sql.ast.Identifier> {
        val values = (expression.expressionsArg.firstOrNull()
            as? dev.brikk.house.sql.ast.Expression)?.expressionsArg ?: emptyList()

        val first = values.getOrNull(0) as? dev.brikk.house.sql.ast.Expression
        val structure =
            if (values.size > 1 && first?.isString == true &&
                values[1] is dev.brikk.house.sql.ast.Tuple
            ) {
                first
            } else {
                null
            }

        return if (structure != null) {
            structure.name.split(",").map { coldef ->
                dev.brikk.house.sql.ast.toIdentifier(coldef.trim().split(" ")[0])!!
            }
        } else {
            val width = (values.getOrNull(0) as? dev.brikk.house.sql.ast.Expression)
                ?.expressionsArg?.size ?: 0
            (1..width).map { dev.brikk.house.sql.ast.toIdentifier("c$it")!! }
        }
    }

    // sqlglot: ClickHouse.NORMALIZATION_STRATEGY = NormalizationStrategy.CASE_SENSITIVE
    override val normalizationStrategy: NormalizationStrategy
        get() = NormalizationStrategy.CASE_SENSITIVE

    // sqlglot: ClickHouse.EXPRESSION_METADATA (sqlglot/typing/clickhouse.py)
    override val expressionMetadata get() = GeneratedTypingMetadata.CLICKHOUSE

    // Latest pinned stable snapshot. Historical engines select CLICKHOUSE_METADATA.forServerVersion.
    override val functionCatalog: FunctionCatalog get() = CLICKHOUSE_FUNCTION_CATALOG

    override val tokenizerConfig: TokenizerConfig get() = TOKENIZER_CONFIG

    override fun parser(errorLevel: ErrorLevel?): Parser =
        ClickhouseParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        ClickhouseGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)

    companion object {
        /** The generated tables with the editor-only inline INSERT data rule (docs/brikk-extensions.md #56). */
        internal val TOKENIZER_CONFIG: TokenizerConfig = StringEscapeProfiles.CLICKHOUSE.withInlineInsertData()
    }
}
