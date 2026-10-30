package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.DuckdbCreateExtensionRepository
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact

/** Conservative no-bind/diagnostic guard, not a grammar verdict or authorization to load code. */
fun mayContainDuckdbExtensionManagement(sql: String): Boolean {
    val tokens = Tokenizer(Dialects.DUCKDB.tokenizerConfig).tokenizeForEditor(sql).tokens
    fun word(index: Int): String? = tokens.getOrNull(index)?.takeIf {
        it.tokenType != TokenType.IDENTIFIER && it.tokenType != TokenType.STRING && !it.tokenType.name.endsWith("_STRING")
    }?.text?.uppercase()
    return tokens.indices.any { index -> word(index) in setOf("INSTALL", "LOAD") ||
        word(index) == "EXTENSION" && word(index + 1) == "REPOSITORY" ||
        word(index) == "UPDATE" && word(index + 1) == "EXTENSIONS" }
}

internal const val REDACTED_EXTENSION_DIAGNOSTIC = "DuckDB extension-management diagnostic; repository details redacted"

/** Successful CREATE returns the stored identity/fingerprints, not the literal keys or a table. */
internal fun Expression.duckdbExtensionResult(): RelationFact? = if (this is DuckdbCreateExtensionRepository) {
    RelationFact(listOf("repository_name" to "VARCHAR", "prefix" to "VARCHAR", "key_fingerprints" to "VARCHAR[]").map { (name, type) ->
        ColumnFact(name, type, nullability = Nullability.NON_NULL, origin = ColumnOrigin.COMPUTED)
    })
} else null
