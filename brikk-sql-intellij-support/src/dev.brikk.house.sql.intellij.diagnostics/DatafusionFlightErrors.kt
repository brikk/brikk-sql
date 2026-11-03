package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.openapi.util.TextRange
import dev.brikk.house.sql.intellij.pipe.PipeRunStatement
import dev.brikk.house.sql.parser.ColumnUnit
import dev.brikk.house.sql.verify.VerifyError

/** Only positions actually returned by the pinned Flight SQL parser error channel. No word-search guesses. */
public object DatafusionFlightErrors {
    private val position = Regex(" at Line: ([1-9][0-9]*), Column: ([1-9][0-9]*)$")

    private fun coordinates(sql: String, message: String): Pair<Int, Int>? {
        val engine = message.removePrefix("Error while executing SQL \"$sql\": ")
        if (!engine.startsWith("Expected ") && !engine.startsWith("SQL error:") && !engine.startsWith("TokenizerError")) return null
        val detail = if (engine.startsWith("SQL error: ParserError(\"") || engine.startsWith("SQL error: TokenizerError(\""))
            engine.removeSuffix("\")") else engine
        val match = position.find(detail) ?: return null
        val line = match.groupValues[1].toIntOrNull() ?: return null
        val column = match.groupValues[2].toIntOrNull() ?: return null
        return line to column
    }

    /** Native columns count code points. The returned offset/column use UTF-16, as the verifier API requires. */
    public fun syntaxError(sql: String, message: String): VerifyError = coordinates(sql, message)?.let { (line, column) ->
        VerifyError.at(sql, message, line, column, ColumnUnit.CODE_POINT)
    } ?: VerifyError(message)

    /** The PIPE callback expects native coordinates, and refuses stale or parameter-expanded source maps. */
    public fun authoredRange(statement: PipeRunStatement, message: String): TextRange? =
        coordinates(statement.sql, message)?.let { (line, column) -> statement.sourceMap?.invoke(line, column) }
}
