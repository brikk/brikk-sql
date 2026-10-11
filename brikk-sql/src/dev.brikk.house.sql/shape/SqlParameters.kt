package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Semicolon
import dev.brikk.house.sql.dialects.Dialects

/**
 * Exact authored parameter ranges without rendering or lowering. Parse/token/shape refusals
 * propagate: callers must not treat an unparseable input as proof of no parameters.
 * IDE-configured textual substitutions remain a separate platform concern.
 */
fun parseParameterOccurrences(sql: String, dialect: String): List<ParameterOccurrence> {
    val source = Dialects.forName(dialect)
    val tokens = source.tokenize(sql)
    val parser = source.parser().also { it.captureSourceRanges = true }
    val statements = parser.parse(tokens, sql).filterNotNull().filterNot { it is Semicolon }
    if (statements.size != 1) throw ShapeError("Parameter discovery requires exactly one statement")
    return parameterOccurrences(statements.single(), tokens)
}
