package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.BaseTokenizerTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.PostgresTokenizerTables
import dev.brikk.house.sql.parser.StringFormat
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Apache DataFusion dialect — brikk-native, NO sqlglot oracle.
 *
 * sqlglot ships no DataFusion dialect, so this is not a Python-oracle port. It is a
 * thin, flag-tuned child of the BASE dialect modeled on polyglot's standalone
 * DataFusion dialect (reference/polyglot/crates/polyglot-sql/src/dialects/datafusion.rs)
 * cross-checked against the DataFusion SQL reference
 * (https://datafusion.apache.org/user-guide/sql/). DataFusion parses via sqlparser-rs
 * GenericDialect: double-quote identifiers, unquoted -> lowercase, `::` casts,
 * TRY_CAST/arrow_cast passthrough, QUALIFY, star EXCEPT/EXCLUDE, LEFT SEMI/ANTI,
 * aggregate FILTER, postgres regex ops, plural interval units, lowercase function
 * rendering, LIMIT/OFFSET, COPY TO (no INTO).
 *
 * Provenance style throughout this trio is `// brikk: ...` (there is no `// sqlglot:`
 * source to cite). Fixtures are supplemented by the pinned native reference tools
 * under tools/datafusion-reference.
 *
 * BASE supplies double-quote identifiers, nested comments, and pipes. Native
 * evidence also requires regex/array operators, dollar strings/parameters, E-strings,
 * and hex literals. These local overlays do not import PostgreSQL's full grammar.
 *
 * Normalization: BASE default is already LOWERCASE (Dialect.normalizationStrategy),
 * which matches DataFusion's unquoted -> lowercase folding; no override needed, but
 * we pin it explicitly for documentation.
 */
// brikk: no sqlglot oracle — flags per polyglot datafusion.rs + DataFusion SQL docs
open class DatafusionDialect : Dialect() {

    override val name: String get() = "datafusion"

    override val functionCatalog get() = dev.brikk.house.sql.metadata.DATAFUSION_FUNCTION_CATALOG

    // brikk: DataFusion folds unquoted identifiers to lowercase (== BASE default).
    override val normalizationStrategy get() = NormalizationStrategy.LOWERCASE

    // brikk: native GenericDialect lexical forms, independently of Spice's PG profile.
    override val tokenizerConfig: TokenizerConfig get() = TOKENIZER_CONFIG

    // brikk: BASE supplies the grammar; DatafusionParser adds regex token handling,
    // one-argument generate_series, and source-spelling metadata for readable identity.
    override fun parser(errorLevel: ErrorLevel?): Parser =
        DatafusionParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        DatafusionGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)

    companion object {
        // brikk: BASE tables with `~` remapped to RLIKE (see [tokenizerConfig] KDoc).
        val TOKENIZER_CONFIG: TokenizerConfig = TokenizerConfig(
            singleTokens = BaseTokenizerTables.SINGLE_TOKENS + ('$' to TokenType.HEREDOC_STRING),
            keywords = BaseTokenizerTables.KEYWORDS + mapOf(
                "~" to TokenType.RLIKE, "@>" to TokenType.AT_GT, "<@" to TokenType.LT_AT,
            ),
            formatStrings = BaseTokenizerTables.FORMAT_STRINGS + mapOf(
                "$" to StringFormat("$", TokenType.HEREDOC_STRING),
                "e'" to StringFormat("'", TokenType.BYTE_STRING),
                "E'" to StringFormat("'", TokenType.BYTE_STRING),
                "x'" to StringFormat("'", TokenType.HEX_STRING),
                "X'" to StringFormat("'", TokenType.HEX_STRING),
            ),
            byteStringEscapes = setOf('\'', '\\'),
            heredocTagIsIdentifier = true,
            heredocStringAlternative = TokenType.PARAMETER,
            hasHexStrings = true,
            unescapedSequences = PostgresTokenizerTables.UNESCAPED_SEQUENCES,
        ).withStringEscapes(
            numericEscapes = PostgresTokenizerTables.NUMERIC_ESCAPES,
            dropUnknownEscapes = true,
        ).withStrictEncodedStrings()
    }
}
