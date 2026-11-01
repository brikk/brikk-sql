package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import kotlin.Boolean
import kotlin.Int

internal data class ClickhouseDecimalShape(val precision: Int, val storage: Int, val scale: Int)

internal fun clickhouseDecimalShape(type: DataType): ClickhouseDecimalShape? {
    val parameters = type.expressionsArg.map {
        val value = if (it is DataTypeParam) it.thisArg else it
        (value as? Literal)?.name?.toIntOrNull() ?: return null
    }
    val (precision, scale) = when (type.thisArg) {
        DType.DECIMAL -> {
            if (parameters.size != 2) return null
            parameters[0] to parameters[1]
        }
        DType.DECIMAL32, DType.DECIMAL64, DType.DECIMAL128, DType.DECIMAL256 -> {
            val precision = when (type.thisArg) {
                DType.DECIMAL32 -> 9; DType.DECIMAL64 -> 18; DType.DECIMAL128 -> 38; else -> 76
            }
            precision to (parameters.singleOrNull() ?: return null)
        }
        else -> return null
    }
    if (precision !in 1..76 || scale !in 0..precision) return null
    return ClickhouseDecimalShape(precision, listOf(9, 18, 38, 76).first { it >= precision }, scale)
}

/** Exact tagged DecimalUtils/FunctionBinaryArithmetic rules, independent of integer division mode. */
internal fun clickhouseDecimalDivisionResult(left: DataType?, right: DataType?, checkOverflow: Boolean?): DataType? {
    if (left == null || right == null || left.args["nullable"] !is Boolean || right.args["nullable"] !is Boolean) return null
    val a = clickhouseDecimalShape(left)
    val b = clickhouseDecimalShape(right)
    val result = when {
        a != null && b != null -> {
            val storage = maxOf(a.storage, b.storage)
            // Avoid unproved dry-run scale-factor dispatch and setting-dependent type failures.
            if (b.scale > a.scale || a.scale + b.scale > storage && checkOverflow != false) return null
            ClickhouseDecimalShape(storage, storage, a.scale)
        }
        a != null && clickhouseIntegerShape(right) != null -> a
        b != null && clickhouseIntegerShape(left) != null -> b
        else -> return null
    }
    return DataType(args("this" to DType.DECIMAL, "nested" to false,
        "nullable" to (left.args["nullable"] == true || right.args["nullable"] == true),
        "expressions" to listOf(result.precision, result.scale).map { DataTypeParam(args("this" to Literal.number(it.toString()))) }))
}
