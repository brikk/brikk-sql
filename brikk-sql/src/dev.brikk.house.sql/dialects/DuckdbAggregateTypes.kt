package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*

private fun Expression.sumLiteralAtom(): Literal? = when (this) {
    is Literal -> this
    is Neg, is Paren -> (thisArg as? Expression)?.sumLiteralAtom()
    else -> null
}

/** Native SUM overloads, not the historical SQLGlot aggregate promotion table. */
internal fun Sum.duckdbNativeSumType(): DataType {
    fun unknown() = DataType.build(DType.UNKNOWN)
    if ((parent as? Dot)?.expressionArg === this) return unknown()
    var value = thisArg as? Expression ?: return unknown()
    if (value is Distinct) value = value.expressionsArg.singleOrNull() as? Expression ?: return unknown()
    value = value.unnest()
    // Generic literal annotations are not exact native wide-integer/decimal facts.
    val atom = value.sumLiteralAtom()
    if (atom?.isNumber == true && (!atom.isInt || atom.name.toIntOrNull() == null)) return unknown()
    val input = value.type as? DataType ?: return unknown()
    return when (input.thisArg) {
        DType.BOOLEAN, DType.NULL, DType.TINYINT, DType.SMALLINT, DType.INT, DType.BIGINT, DType.INT128,
        DType.UTINYINT, DType.USMALLINT, DType.UINT, DType.UBIGINT -> DataType.build(DType.INT128)
        DType.UINT128, DType.FLOAT, DType.DOUBLE -> DataType.build(DType.DOUBLE)
        DType.DECIMAL -> {
            val parameters = input.expressionsArg.filterIsInstance<Expression>()
            val numbers = parameters.map { (it.thisArg as? Literal)?.takeIf { it.isInt }?.name?.toIntOrNull() }
            if (numbers.size != 2 || numbers[0]?.let { it in 1..38 } != true || numbers[1]?.let { it in 0..numbers[0]!! } != true) return unknown()
            (input.copy() as DataType).apply { set("expressions", listOf(DataTypeParam(args("this" to Literal.number(38))), parameters[1].copy())) }
        }
        else -> unknown()
    }
}
