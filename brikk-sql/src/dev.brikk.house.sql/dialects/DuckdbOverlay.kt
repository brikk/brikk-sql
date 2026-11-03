package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Overlay
import dev.brikk.house.sql.ast.Kwarg
import dev.brikk.house.sql.ast.PropertyEQ

/** Only the unqualified builtin, not a schema-owned function with the same name. */
internal fun Expression.isDuckdbOverlayCall(): Boolean =
    (parent as? Dot)?.expressionArg !== this && (this is Overlay || this is Anonymous && name.equals("overlay", true))

/** Bind roles without reordering SQL; the quoted ordinary-call form also accepts named arguments. */
internal fun Expression.duckdbOverlayArguments(): List<Expression>? {
    if (!isDuckdbOverlayCall()) return null
    if (this is Anonymous) {
        val arguments = expressionsArg.filterIsInstance<Expression>()
        if (arguments.size !in 3..4) return null
        if (arguments.none { it is Kwarg || it is PropertyEQ }) return arguments
        val parameters = listOf("string", "replacement", "start", "count").take(arguments.size)
        val values = arrayOfNulls<Expression>(parameters.size)
        var named = false
        for ((index, argument) in arguments.withIndex()) {
            val position = if (argument is Kwarg || argument is PropertyEQ) {
                named = true
                parameters.indexOf((argument.thisArg as? Expression)?.name?.lowercase())
            } else {
                if (named) return null
                index
            }
            if (position !in values.indices || values[position] != null) return null
            values[position] = if (argument is Kwarg || argument is PropertyEQ) argument.expressionArg as? Expression else argument
        }
        return values.filterNotNull().takeIf { it.size == parameters.size }
    }
    val required = listOf("this", "expression", "from_").map { args[it] as? Expression ?: return null }
    return required + listOfNotNull(args["for_"] as? Expression)
}
