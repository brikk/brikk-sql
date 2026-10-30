package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Kwarg
import dev.brikk.house.sql.ast.PropertyEQ

/** Pinned native path helpers only, never a qualified same-name UDF. */
internal fun Expression.duckdbPathFunctionName(): String? =
    if (this is Anonymous && (parent as? Dot)?.expressionArg !== this)
        name.uppercase().takeIf { it in setOf("HIVE_PARTITION_COMPONENT", "PATH_JOIN") } else null

/** Bind roles only for type/NULL evidence. Authored SQL order is never changed. */
internal fun Expression.duckdbPathArguments(): List<Expression>? {
    val name = duckdbPathFunctionName() ?: return null
    val values = expressionsArg.filterIsInstance<Expression>()
    if (name == "PATH_JOIN") {
        val single = values.singleOrNull()
        if ((single is Kwarg || single is PropertyEQ) && (single.thisArg as? Expression)?.name.equals("path", true))
            return listOfNotNull(single.expressionArg as? Expression).takeIf { it.size == 1 }
        return values.takeIf { it.isNotEmpty() && it.none { value -> value is Kwarg || value is PropertyEQ } }
    }
    if (values.size != 2) return null
    val parameters = listOf("name", "value")
    val bound = arrayOfNulls<Expression>(2)
    var named = false
    for ((index, value) in values.withIndex()) {
        val position = if (value is Kwarg || value is PropertyEQ) {
            named = true
            parameters.indexOf((value.thisArg as? Expression)?.name?.lowercase())
        } else { if (named) return null; index }
        if (position !in bound.indices || bound[position] != null) return null
        bound[position] = if (value is Kwarg || value is PropertyEQ) value.expressionArg as? Expression else value
    }
    return bound.filterNotNull().takeIf { it.size == 2 }
}
