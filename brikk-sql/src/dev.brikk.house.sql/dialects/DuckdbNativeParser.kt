package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.DuckdbRegexOperator
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser

// Ported from SQLGlot f31f109daef0ca99347d36295534a0d59956059c.
// SORT is an identifier before INSERT BY NAME, not a Hive SORT BY clause.
internal val nativeDuckdbTokenizerConfig = DuckdbParserTables.TOKENIZER_CONFIG.withoutKeywords(setOf("SORT BY"))

/** Native execution parser. The ordinary dialect parser retains the historical SQLGlot
 * regex AST for parity, which is not a DuckDB 2.0 operator execution contract.
 */
fun nativeDuckdbParser(errorLevel: ErrorLevel? = null): DuckdbParser =
    DuckdbParser(errorLevel = errorLevel, tokenizerConfig = nativeDuckdbTokenizerConfig).apply { nativeRegexOperators = true }

/** Exactly one native DuckDB statement, with serializable session-dependent regex operators. */
fun parseDuckdbNativeOne(sql: String): Expression {
    val statements = nativeDuckdbParser().parse(nativeDuckdbAnalysisDialect().tokenize(sql), sql)
        .filterNotNull().filterNot { it is dev.brikk.house.sql.ast.Semicolon }
    if (statements.size != 1) throw ParseError("Expected exactly one native DuckDB statement")
    return statements.single()
}

internal fun Parser.useNativeDuckdbOperators(): Parser = apply {
    if (this is DuckdbParser) nativeRegexOperators = true
}

internal fun Expression.hasDuckdbRegexSelector(): Boolean = walk().any {
    if (it !is DuckdbRegexOperator) false else {
        val left = (it.thisArg as? Expression)?.unnest()
        left is Star || left is Column && left.thisArg is Star
    }
}
