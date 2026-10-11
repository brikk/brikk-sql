package dev.brikk.house.sql.parser

/** Stable aliases and generation helpers; policies now come from the coherent generated tables. */
object StringEscapeProfiles {
    val MYSQL: TokenizerConfig = MysqlTokenizerTables.CONFIG
    val DORIS: TokenizerConfig = DorisTokenizerTables.CONFIG
    val STARROCKS: TokenizerConfig = StarrocksTokenizerTables.CONFIG
    val DUCKDB: TokenizerConfig = DuckdbTokenizerTables.CONFIG
    val POSTGRES: TokenizerConfig = PostgresTokenizerTables.CONFIG
    val CLICKHOUSE: TokenizerConfig = ClickhouseTokenizerTables.CONFIG

    // sqlglot: _Dialect.ESCAPED_SEQUENCES. Do not generate escapes which just drop a backslash.
    fun escapedSequences(config: TokenizerConfig): Map<String, String> = buildMap {
        for ((escape, value) in config.unescapedSequences) {
            if (value == "\\" || (value.any { it.code < 32 || it.code in 127..159 } && escape.drop(1) != value)) {
                put(value, escape)
            }
        }
        val format = when {
            'x' in config.numericEscapes -> 'x' to 16
            '0' in config.numericEscapes -> '0' to 8
            'u' in config.numericEscapes -> 'u' to 16
            else -> return@buildMap
        }
        for (code in (0..31) + 127) {
            if (code in listOf(9, 10, 13)) continue
            val prefix = if (format.first == '0') "\\" else "\\${format.first}"
            val width = when (format.first) { 'x' -> 2; '0' -> 3; else -> 4 }
            val value = code.toChar().toString()
            if (value !in this) put(value, prefix + code.toString(format.second).padStart(width, '0'))
        }
    }
}
