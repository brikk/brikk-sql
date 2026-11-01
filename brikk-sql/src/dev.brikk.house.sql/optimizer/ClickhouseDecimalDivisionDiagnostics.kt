package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List

private data class DecimalConstant(val magnitude: String, val negative: Boolean)

private fun scaled(magnitude: String, scale: kotlin.Int): String =
    if (magnitude == "0") "0" else magnitude + "0".repeat(scale)

/** No floating conversion, exponent parsing, rounding, truncation or arbitrary constant folding. */
private fun decimalConstant(raw: Expression, type: DataType): DecimalConstant? {
    val node = raw.unnest()
    val shape = clickhouseDecimalShape(type)
    if (shape == null) {
        if (clickhouseIntegerShape(type) == null) return null
        val (magnitude, negative) = clickhouseExactIntegerConstant(node) ?: return null
        return DecimalConstant(magnitude, negative)
    }
    if (node !is Cast || node is TryCast) return null
    val source = (node.thisArg as? Expression)?.unnest() ?: return null
    val value = if (source is Literal && source.isString) {
        if (source.name.length > 79) return null
        val negative = source.name.startsWith('-')
        val parts = source.name.removePrefix("-").split('.')
        if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { char -> char !in '0'..'9' } }) return null
        val fraction = parts.getOrNull(1) ?: ""
        if (fraction.length > shape.scale) return null
        val coefficient = (parts[0] + fraction.padEnd(shape.scale, '0')).trimStart('0').ifEmpty { "0" }
        DecimalConstant(coefficient, negative && coefficient != "0")
    } else {
        val (magnitude, negative) = clickhouseExactIntegerConstant(source) ?: return null
        DecimalConstant(scaled(magnitude, shape.scale), negative)
    }
    return value.takeIf { it.magnitude.length <= shape.precision }
}

/** Invalid constant casts must not be used to assert a later division's native error category. */
internal fun clickhouseDecimalConstantProved(node: Expression, type: DataType?): Boolean =
    type != null && decimalConstant(node, type) != null

/** The native return-type overflow guard precedes NULL execution masking and scale-factor setup. */
internal fun clickhouseDecimalDivisionTypeFailure(types: List<DataType?>, checkOverflow: Boolean?,
    allowScaleDispatch: Boolean): ClickhouseCompositionRule? {
    val a = types[0]?.let(::clickhouseDecimalShape) ?: return null
    val b = types[1]?.let(::clickhouseDecimalShape) ?: return null
    val overflowsScale = a.scale + b.scale > maxOf(a.storage, b.storage)
    if (overflowsScale && checkOverflow == true) return ClickhouseCompositionRule.DECIMAL_DIVISION_SCALE_OVERFLOW
    if (allowScaleDispatch && b.scale > a.scale && (!overflowsScale || checkOverflow == false))
        return ClickhouseCompositionRule.DECIMAL_DIVISION_SCALE
    return null
}

/** Prove zero only after exact non-NULL casts and all possibly preceding narrowing/scaling guards. */
internal fun clickhouseDecimalDivisionZero(operands: List<Expression>, types: List<DataType?>,
    result: DataType, checkOverflow: Boolean?, checkCanceled: () -> Unit): Boolean {
    val resultShape = clickhouseDecimalShape(result) ?: return false
    val values = operands.mapIndexed { index, node ->
        checkCanceled()
        decimalConstant(node, types[index] ?: return false) ?: return false
    }
    if (values[1].magnitude != "0") return false
    if (checkOverflow == false) return true
    val bits = when (resultShape.storage) { 9 -> 32; 18 -> 64; 38 -> 128; else -> 256 }
    val left = values[0]
    if (!clickhouseFitsSignedMagnitude(left.magnitude, left.negative, bits)) return false
    val right = types[1]?.let(::clickhouseDecimalShape)
    if (right != null) {
        val leftIsDecimal = types[0]?.let(::clickhouseDecimalShape) != null
        val exponent = right.scale * if (leftIsDecimal) 1 else 2
        // Integer/Decimal squares the multiplier before using the numerator, even when it is zero.
        if (!leftIsDecimal && !clickhouseFitsSignedMagnitude(scaled("1", exponent), false, bits)) return false
        if (!clickhouseFitsSignedMagnitude(scaled(left.magnitude, exponent), left.negative, bits)) return false
    }
    return true
}
