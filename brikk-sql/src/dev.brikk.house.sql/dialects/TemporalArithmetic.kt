package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.generator.Generator
import kotlin.Boolean
import kotlin.String

/** sqlglot #8815: replacing a function by +/- must retain its precedence in its parent. */
internal fun Generator.dateDeltaToBinarySql(
    original: Expression,
    timestamp: Expression?,
    interval: Expression,
    subtract: Boolean,
): String {
    // Detached operands keep generation(copy=false) from changing the caller's parent
    // links, literals or subsequent renderings. SQLGlot's arithmetic builders also group
    // Binary operands; this port's raw Args constructors do not do that automatically.
    fun operand(value: Expression?): Expression? = value?.copy()?.let {
        if (it is Binary) Paren(args("this" to it)) else it
    }
    val operands = args("this" to operand(timestamp), "expression" to operand(interval))
    val binary = if (subtract) Sub(operands) else Add(operands)
    val parent = original.parent
    val grouped = (parent is Binary || parent is Unary || parent is AtTimeZone) &&
        parent !is Paren && !((parent is Add || parent is Sub) && parent.thisArg === original)
    return sql(if (grouped) Paren(args("this" to binary)) else binary)
}
