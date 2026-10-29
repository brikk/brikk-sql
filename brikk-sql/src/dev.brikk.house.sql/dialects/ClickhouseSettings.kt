package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.generator.Generator
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List

/** Query-local evidence only. Missing, parameterized and non-boolean values leave session state unknown. */
internal fun clickhouseBooleanSetting(node: Expression, name: String): Boolean? {
    var current: Expression? = node
    while (current != null) {
        val settings = current.args["settings"]
        val entries = when (settings) {
            is Expression -> settings.expressionsArg
            is List<*> -> settings
            else -> emptyList<kotlin.Any?>()
        }
        val entry = entries.filterIsInstance<Expression>().lastOrNull {
            it is EQ && (it.thisArg as? Expression)?.name.equals(name, ignoreCase = true)
        }
        if (entry != null) {
            val value = entry.expressionArg as? Expression
            return when {
                value is dev.brikk.house.sql.ast.Boolean -> value.thisArg as? Boolean
                value is Literal && value.name.lowercase() in setOf("1", "true") -> true
                value is Literal && value.name.lowercase() in setOf("0", "false") -> false
                else -> null
            }
        }
        current = current.parent
    }
    return null
}

/** Diagnostic only: do not invent a session setting or rewrite a potentially erroring integer division. */
internal fun checkClickhouseDivision(generator: Generator, expression: Div) {
    val source = generator.sourceDialect?.lowercase() ?: return
    val target = generator.dialect.name
    if (source == target || source != "clickhouse" && target != "clickhouse") return
    fun real(e: Expression): Boolean = e.isType(DType.FLOAT, DType.DOUBLE) ||
        e is Literal && !e.isString && ('.' in e.name || 'e' in e.name.lowercase()) ||
        e is Cast && (e.args["to"] as? DataType)?.isType(DType.FLOAT, DType.DOUBLE) == true
    if (real(expression.left) || real(expression.right)) return
    val setting = clickhouseBooleanSetting(expression, "sql_compatible_division_for_integers")
    if (setting == false) return
    val message = "ClickHouse integer / depends on sql_compatible_division_for_integers; " +
        "the ${if (source == "clickhouse") "source" else "target"} session setting is ${if (setting == true) "enabled" else "unknown"}. " +
        "Fractional results, truncation, overflow and division-by-zero behavior are not interchangeable"
    if (setting == true && source == "clickhouse") generator.unexecutable(message)
    else generator.unsupported(message)
}
