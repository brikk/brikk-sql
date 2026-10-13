package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.PIPE_STAGES_RUN_EVERYWHERE
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.StringEscapeProfiles

/**
 * Port of sqlglot's DuckDB dialect umbrella (reference/sqlglot/sqlglot/dialects/duckdb.py
 * class DuckDB). Tokenizer tables are generated (DuckdbTokenizerTables); the parser and
 * generator subclasses live in DuckdbParser.kt / DuckdbGenerator.kt.
 */
// sqlglot: dialects.duckdb.DuckDB
open class DuckdbDialect : Dialect() {
    // brikk-native PIPE evidence: DuckDB 1.5.6 ran every lowered stage below.
    override val pipeRunnableStages: Set<String> get() = PIPE_STAGES_RUN_EVERYWHERE + setOf(
        "CALL", "DROP", "SET", "RENAME", "PIVOT", "UNPIVOT", "TABLESAMPLE", "UNION", "ASOF JOIN", "NATURAL JOIN", "POSITIONAL JOIN",
    )


    override val name: String get() = "duckdb"
    override val usingColumnOrder: UsingColumnOrder get() = UsingColumnOrder.IN_PLACE
    override val qualifiedStarUsing: QualifiedStarUsing get() = QualifiedStarUsing.RAW

    // sqlglot: DuckDB.EXPRESSION_METADATA (sqlglot/typing/duckdb.py)
    override val expressionMetadata get() = dev.brikk.house.sql.ast.GeneratedTypingMetadata.DUCKDB

    // sqlglot: DuckDB.NORMALIZATION_STRATEGY
    override val normalizationStrategy get() = NormalizationStrategy.CASE_INSENSITIVE

    // sqlglot: DuckDB.ASCII_ONLY_NORMALIZATION = True
    override val asciiOnlyNormalization: Boolean get() = true

    // sqlglot #8557: table aliases may be referenced as whole-row struct values.
    override val tablesReferenceableAsColumns: Boolean get() = true

    // Generated from the embedded engine's duckdb_functions() registry
    // (tools/generate_duckdb_functions.py).
    override val functionCatalog: FunctionCatalog get() = DUCKDB_FUNCTION_CATALOG

    override val tokenizerConfig: TokenizerConfig get() = StringEscapeProfiles.DUCKDB

    // sqlglot: DuckDB.TIME_MAPPING is the base (empty) mapping; only
    // INVERSE_TIME_MAPPING carries dialect-specific entries (see DuckdbGenerator).

    override fun parser(errorLevel: ErrorLevel?): Parser =
        DuckdbParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        DuckdbGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)
}
