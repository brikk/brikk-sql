package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.DuckdbCreateExtensionRepository
import dev.brikk.house.sql.ast.DuckdbDropExtensionRepository
import dev.brikk.house.sql.ast.DuckdbInstallExtension
import dev.brikk.house.sql.ast.DuckdbLoadExtension
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.generator.Generator
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

internal fun Generator.duckdbExtensionSql(expression: Expression): String {
    if (!dialect.executesExtensionManagement) unexecutable("$targetName cannot execute DuckDB extension/repository management")
    val target = sql(expression, "this")
    val from = sql(expression, "from_").let { if (it.isEmpty()) "" else " FROM $it" }
    return when (expression) {
        is DuckdbCreateExtensionRepository -> {
            val replace = if (expression.args["replace"] == true) " OR REPLACE" else ""
            val exists = if (expression.args["exists"] == true) " IF NOT EXISTS" else ""
            val keys = expressions(expression).let { if (it.isEmpty()) "" else " USING PUBLIC ${if (expression.expressionsArg.size == 1) "KEY" else "KEYS"} $it" }
            "CREATE$replace EXTENSION REPOSITORY$exists $target WITH PREFIX ${sql(expression, "prefix")}$keys"
        }
        is DuckdbDropExtensionRepository -> "DROP EXTENSION REPOSITORY${if (expression.args["exists"] == true) " IF EXISTS" else ""} $target"
        is DuckdbInstallExtension -> {
            val force = if (expression.args["force"] == true) "FORCE " else ""
            val load = if (expression.args["load"] == true) " AND LOAD" else ""
            val version = sql(expression, "version").let { if (it.isEmpty()) "" else " VERSION $it" }
            "${force}INSTALL$load $target$from$version"
        }
        is DuckdbLoadExtension -> "LOAD $target$from" + sql(expression, "alias").let { if (it.isEmpty()) "" else " AS $it" }
        else -> error("Not an extension management statement")
    }
}
