package dev.brikk.house.sql.parser

import dev.brikk.house.sql.parser.TokenizerConfig.NumericEscape

/** Hand-owned target policies until the coherent generated-table refresh (#8470 / #8484). */
object StringEscapeProfiles {
    val DUCKDB: TokenizerConfig = DuckdbTokenizerTables.CONFIG.withStringEscapes(
        numericEscapes = mapOf('x' to NumericEscape(16, 1, 2, 0xFF), '0' to NumericEscape(8, 1, 3, 0x1FF)),
        numericEscapesAreBytes = true, dropUnknownEscapes = true,
        unescapedSequences = DuckdbTokenizerTables.UNESCAPED_SEQUENCES + mapOf("\\a" to "a", "\\v" to "v"),
    )

    val POSTGRES: TokenizerConfig = PostgresTokenizerTables.CONFIG.withStringEscapes(
        numericEscapes = mapOf('x' to NumericEscape(16, 1, 2, 0xFF), '0' to NumericEscape(8, 1, 3, 0x1FF),
            'u' to NumericEscape(16, 4, 4, 0xFFFF), 'U' to NumericEscape(16, 8, 8, 0x10FFFF)),
        numericEscapesAreBytes = true, dropUnknownEscapes = true,
        unescapedSequences = PostgresTokenizerTables.UNESCAPED_SEQUENCES + mapOf("\\a" to "a"),
    )

    val CLICKHOUSE: TokenizerConfig = ClickhouseTokenizerTables.CONFIG.withStringEscapes(
        numericEscapes = mapOf('x' to NumericEscape(16, 2, 2, 0xFF)), numericEscapesAreBytes = true,
        unescapedSequences = ClickhouseTokenizerTables.UNESCAPED_SEQUENCES + mapOf(
            "\\e" to "\u001B", "\\N" to "", "\\/" to "/", "\\\"" to "\"", "\\=" to "=", "\\`" to "`",
        ) + (1..31).associate { "\\${it.toChar()}" to it.toChar().toString() },
    )

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
