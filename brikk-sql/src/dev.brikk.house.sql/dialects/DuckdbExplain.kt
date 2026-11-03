package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import kotlin.String
import kotlin.Boolean

/** EXPLAIN binds its child; it is never a read-only editor-validation fallback. */
fun mayContainDuckdbExplain(sql: String): Boolean = Tokenizer(Dialects.DUCKDB.tokenizerConfig).tokenizeForEditor(sql).tokens.any {
    it.tokenType != TokenType.IDENTIFIER && it.tokenType != TokenType.STRING && !it.tokenType.name.endsWith("_STRING") &&
        it.text.equals("EXPLAIN", ignoreCase = true)
}

private fun DuckdbExplainOption.optionName(): String {
    val identifier = thisArg as? Identifier
    return if (identifier?.quoted != true && name.equals("ANALYSE", true)) "analyze" else name.lowercase()
}

internal fun DuckdbExplain.analyzesChild(): Boolean = args["analyze"] == true ||
    expressionsArg.filterIsInstance<DuckdbExplainOption>().any { it.optionName() == "analyze" }

internal fun DuckdbExplain.exportsSql(): Boolean = expressionsArg.filterIsInstance<DuckdbExplainOption>().any { it.optionName() == "sql" }

/** Known native errors and unmodeled contexts; messages never echo option values or child SQL. */
internal fun DuckdbExplain.explainProblems(): List<String> = buildList {
    val options = expressionsArg.filterIsInstance<DuckdbExplainOption>()
    val sql = options.filter { it.optionName() == "sql" }
    val formats = options.filter { it.optionName() == "format" }
    if (sql.size > 1 || sql.any { it.args["expression"] != null }) add("EXPLAIN SQL must occur once without arguments")
    if (sql.isNotEmpty() && (analyzesChild() || formats.isNotEmpty())) add("EXPLAIN SQL cannot be combined with ANALYZE or FORMAT")
    if (formats.size > 1) add("EXPLAIN FORMAT cannot occur more than once")
    val knownFormats = setOf("text", "json", "yaml", "html", "graphviz", "mermaid", "compact", "default", "query_tree", "query_tree_optimizer", "no_output")
    for (option in formats) {
        var value = option.args["expression"] as? Expression
        while (value is Paren) value = value.thisArg as? Expression
        val format = when (value) {
            is Literal -> value.name.takeIf { value.isString }
            is Column, is Identifier, is Var -> value.name
            else -> value?.takeIf { it.key == "default" }?.key
        }?.lowercase()
        if (format == "web") add("EXPLAIN FORMAT WEB requires a client-specific renderer; executable support is not verified")
        else if (format !in knownFormats) add("EXPLAIN FORMAT requires a supported constant format name or DEFAULT")
    }
    if (options.any { it.optionName() !in setOf("sql", "format", "analyze") }) add("Unsupported DuckDB EXPLAIN option")
    if (options.filter { it.optionName() == "analyze" }.any { option ->
            (option.args["expression"] as? Expression)?.walk()?.any { it is Placeholder || it is Parameter } == true
        }) add("Parameter binding inside ignored ANALYZE option arguments is not modeled")
    val body = thisArg as? Expression
    if (body is Command) add("Executable EXPLAIN requires a structured child statement")
    if (exportsSql() && body !is Query && body !is Values && body !is Insert && body !is Update && body !is Delete && body !is Merge && body !is PipeQuery)
        add("EXPLAIN SQL supports query and INSERT/UPDATE/DELETE/MERGE bodies only")
}

internal fun Expression.duckdbExplainResult(): RelationFact? {
    if (this !is DuckdbExplain || explainProblems().isNotEmpty()) return null
    return RelationFact(listOf("explain_key", "explain_value").map {
        ColumnFact(it, "VARCHAR", nullability = Nullability.NON_NULL, origin = ColumnOrigin.COMPUTED)
    })
}
