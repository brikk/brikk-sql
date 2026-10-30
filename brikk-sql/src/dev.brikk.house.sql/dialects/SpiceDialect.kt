package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.metadata.SPICE_FUNCTION_CATALOG
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType

/** Spice v2.4.0: PostgreSQL parsing with case-preserving identifiers and Spice extensions. */
class SpiceDialect : DatafusionDialect() {
    override val name: String get() = "spice"
    override val normalizationStrategy get() = NormalizationStrategy.CASE_SENSITIVE
    override val functionCatalog get() = SPICE_FUNCTION_CATALOG
    override val tokenizerConfig get() = TOKENIZER_CONFIG
    override fun parser(errorLevel: ErrorLevel?): Parser = SpiceParser(errorLevel, tokenizerConfig)
    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        SpiceGenerator(pretty = pretty, sourceDialect = sourceDialect)

    companion object {
        val TOKENIZER_CONFIG = DatafusionDialect.TOKENIZER_CONFIG.withKeywords(mapOf(
            "#" to TokenType.HASH,
            // Keep utility-statement tails tokenized instead of becoming opaque Command strings.
            "EXPLAIN" to TokenType.VAR, "PREPARE" to TokenType.VAR,
            "EXECUTE" to TokenType.VAR, "SHOW" to TokenType.VAR,
            "RESET" to TokenType.VAR,
        ))
    }
}
