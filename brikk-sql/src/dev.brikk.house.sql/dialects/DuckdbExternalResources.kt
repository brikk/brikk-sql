package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.DuckdbShowExternalResources
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact

/** A no-bind/diagnostic guard, not permission to call a recipe or discover remote resources. */
fun mayContainDuckdbExternalResources(sql: String): Boolean {
    val tokens = Tokenizer(Dialects.DUCKDB.tokenizerConfig).tokenizeForEditor(sql).tokens
    fun word(index: Int): String? = tokens.getOrNull(index)?.takeIf {
        it.tokenType != TokenType.IDENTIFIER && it.tokenType != TokenType.STRING && !it.tokenType.name.endsWith("_STRING")
    }?.text?.uppercase()
    val calls = setOf("CREATE_EXTERNAL_RESOURCE", "REGISTER_EXTERNAL_RESOURCE", "REGISTER_EXTERNAL_RESOURCE_TYPE",
        "DESTROY_EXTERNAL_RESOURCE", "DUCKDB_EXTERNAL_RESOURCES")
    return tokens.indices.any { index ->
        word(index) == "EXTERNAL" && word(index + 1) in setOf("RESOURCE", "RESOURCES") ||
            tokens[index].let { it.tokenType != TokenType.STRING && !it.tokenType.name.endsWith("_STRING") && it.text.uppercase() in calls } &&
                tokens.getOrNull(index + 1)?.tokenType == TokenType.L_PAREN
    }
}

internal const val REDACTED_RESOURCE_DIAGNOSTIC = "DuckDB external-resource diagnostic; resource details redacted"

/** The handle stays an opaque native MAP, never an inferred provider-specific schema. */
internal fun Expression.duckdbExternalResourceResult(): RelationFact? {
    if (this !is DuckdbShowExternalResources) return null
    return RelationFact(listOf("name", "type", "managed", "state", "reference", "uri", "attached_db_type", "handle").map { name ->
        ColumnFact(name, when (name) {
            "managed" -> "BOOLEAN"
            "handle" -> "MAP(VARCHAR, VARCHAR)"
            else -> "VARCHAR"
        }, nullability = when {
            name in setOf("type", "managed") || name == "name" && args["all"] != true -> Nullability.NON_NULL
            else -> Nullability.NULLABLE
        }, origin = ColumnOrigin.COMPUTED)
    })
}
