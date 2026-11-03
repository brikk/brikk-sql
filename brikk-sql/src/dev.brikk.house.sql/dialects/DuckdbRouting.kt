package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer

/** Conservative diagnostic/no-bind guard, not grammar acceptance or a routing decision. */
fun mayContainDuckdbRouting(sql: String): Boolean {
    val tokens = Tokenizer(Dialects.DUCKDB.tokenizerConfig).tokenizeForEditor(sql).tokens
    return tokens.withIndex().any { (index, token) ->
        token.tokenType != TokenType.IDENTIFIER && token.tokenType != TokenType.STRING &&
            !token.tokenType.name.endsWith("_STRING") &&
            (token.text.equals("DISCONNECT", ignoreCase = true) || token.text.equals("CONNECT", ignoreCase = true) &&
                !tokens.getOrNull(index + 1)?.text.equals("BY", ignoreCase = true))
    }
}

internal const val REDACTED_ROUTING_DIAGNOSTIC = "DuckDB routing diagnostic; connection details redacted"
