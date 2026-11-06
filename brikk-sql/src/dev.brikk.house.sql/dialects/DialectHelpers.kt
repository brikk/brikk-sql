package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.WeekStart
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.NodeFactory
import dev.brikk.house.sql.parser.ParseError

// sqlglot: dialect.unit_to_str
internal fun unitToStr(expression: Expression, default: String = "DAY"): Expression? {
    val unit = expression.args["unit"] as? Expression
        ?: return if (default.isNotEmpty()) Literal.string(default) else null

    if (unit is Placeholder || (unit !is Var && unit !is Literal)) return unit

    return Literal.string(unit.name)
}

// sqlglot: dialect.unit_to_var
internal fun unitToVar(expression: Expression, default: String = "DAY"): Expression? {
    val unit = expression.args["unit"] as? Expression

    if (unit is Var || unit is Placeholder || unit is WeekStart || unit is Column) return unit

    val value = unit?.name ?: default
    return if (value.isNotEmpty()) Var(args("this" to value)) else null
}

// sqlglot: helper.is_int
internal fun isInt(text: String): Boolean = text.toLongOrNull() != null

// sqlglot: dialect.build_date_delta_with_interval (default_unit="DAY")
internal fun buildDateDeltaWithIntervalDefaultDay(
    factory: NodeFactory,
): (List<Expression?>) -> Expression = { argsList ->
    if (argsList.size < 2) throw ParseError("INTERVAL expression expected")
    val interval = argsList[1]
    if (interval !is Interval) {
        factory(
            args(
                "this" to argsList[0],
                "expression" to interval,
                // sqlglot: exp.Literal.string(default_unit), then TimeUnit.__init__ -> Var
                "unit" to normalizeTimeUnit(Literal.string("DAY")),
            )
        )
    } else {
        factory(
            args(
                "this" to argsList[0],
                "expression" to interval.thisArg,
                // sqlglot: unit_to_str(interval), then TimeUnit.__init__ converts to Var
                "unit" to normalizeTimeUnit(unitToStr(interval)),
            )
        )
    }
}
