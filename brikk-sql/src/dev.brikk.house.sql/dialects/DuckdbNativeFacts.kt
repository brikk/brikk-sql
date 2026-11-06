package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.parser.ErrorLevel

/** Native 2.0 analysis defaults, separate from the historical SQLGlot annotation contract.
 * The deprecated legacy_disable_null_type setting is not inferred or changed. Runtime
 * catalogs remain authoritative for tables created under a different session policy.
 */
fun nativeDuckdbAnalysisDialect(): DuckdbDialect = NativeDuckdbFacts

private object NativeDuckdbFacts: DuckdbDialect() {
    override val tokenizerConfig get() = nativeDuckdbTokenizerConfig
    override val supportsNullType: Boolean get() = true
    override val defaultNullType: DType get() = DType.NULL
    override fun parser(errorLevel: ErrorLevel?): DuckdbParser =
        (super.parser(errorLevel) as DuckdbParser).apply { nativeRegexOperators = true }
}

internal fun Dialect.withNativeDuckdbFacts(): Dialect = if (name == "duckdb") NativeDuckdbFacts else this
