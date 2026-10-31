package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import kotlin.Boolean
import kotlin.Int
import kotlin.String

/** Bounded decimal magnitudes avoid a JVM-only big-integer dependency in shared code. */
private data class IntegerConstant(val negative: Boolean, val magnitude: String)

private fun compareMagnitude(a: String, b: String): Int =
    if (a.length == b.length) a.compareTo(b) else a.length.compareTo(b.length)

private fun subtractMagnitude(a: String, b: String): String {
    var borrow = 0
    val result = StringBuilder()
    for (i in a.indices.reversed()) {
        val digit = a[i].digitToInt() - (b.getOrNull(i - a.length + b.length)?.digitToInt() ?: 0) - borrow
        result.append(('0'.code + (digit + 10) % 10).toChar())
        borrow = if (digit < 0) 1 else 0
    }
    return result.reverse().toString().trimStart('0').ifEmpty { "0" }
}

private fun powerOfTwo(bits: Int): String {
    var value = "1"
    repeat(bits) {
        var carry = 0
        val result = StringBuilder()
        for (digit in value.reversed()) {
            val doubled = digit.digitToInt() * 2 + carry
            result.append(('0'.code + doubled % 10).toChar())
            carry = doubled / 10
        }
        if (carry != 0) result.append('1')
        value = result.reverse().toString()
    }
    return value
}

internal fun clickhouseIntegerShape(type: DataType): Pair<Int, Boolean>? = when (type.thisArg) {
    DType.TINYINT -> 8 to true; DType.SMALLINT -> 16 to true; DType.INT -> 32 to true
    DType.BIGINT -> 64 to true; DType.INT128 -> 128 to true; DType.INT256 -> 256 to true
    DType.UTINYINT -> 8 to false; DType.USMALLINT -> 16 to false; DType.UINT -> 32 to false
    DType.UBIGINT -> 64 to false; DType.UINT128 -> 128 to false; DType.UINT256 -> 256 to false
    else -> null
}

private fun fits(value: IntegerConstant, type: DataType): Boolean {
    val (bits, signed) = clickhouseIntegerShape(type) ?: return false
    if (value.negative && !signed) return false
    val exclusive = powerOfTwo(if (signed) bits - 1 else bits)
    val comparison = compareMagnitude(value.magnitude, exclusive)
    return comparison < 0 || signed && value.negative && comparison == 0
}

/** Only lossless literal casts are evaluated. Wrapping casts, arithmetic and NULL remain unknown. */
private fun integerConstant(node: Expression, depth: Int = 0): IntegerConstant? {
    if (depth > 32) return null
    return when (node) {
        is TryCast -> null
        is Cast -> {
            val input = (node.thisArg as? Expression)?.let { integerConstant(it, depth + 1) } ?: return null
            val target = node.args["to"] as? DataType ?: return null
            input.takeIf { fits(it, target) }
        }
        is Paren -> (node.thisArg as? Expression)?.let { integerConstant(it, depth + 1) }
        is Neg -> (node.thisArg as? Expression)?.let { integerConstant(it, depth + 1) }
            ?.let { it.copy(negative = it.magnitude != "0" && !it.negative) }
        is Literal -> {
            // String casts need their own conversion proof; numeric literal syntax alone is used here.
            if (node.isString || node.name.toULongOrNull() == null || node.name.any { it !in '0'..'9' }) null
            else IntegerConstant(false, node.name.trimStart('0').ifEmpty { "0" })
        }
        else -> null
    }
}

internal data class ClickhouseConstantDivisionCheck(val failure: ClickhouseCompositionRule?)

/** null means unproved; a check without a failure only establishes this constant division's bounds. */
internal fun clickhouseConstantDivisionCheck(operands: kotlin.collections.List<Expression>,
    types: kotlin.collections.List<DataType?>, result: DataType, checkCanceled: () -> Unit): ClickhouseConstantDivisionCheck? {
    if (clickhouseIntegerShape(result) == null) return null
    val values = operands.mapIndexed { index, node ->
        checkCanceled()
        val value = integerConstant(node) ?: return null
        if (!fits(value, types[index] ?: return null)) return null
        value
    }
    val (a, b) = values
    if (b.magnitude == "0") return ClickhouseConstantDivisionCheck(ClickhouseCompositionRule.DIVISION_BY_ZERO)
    var remainder = "0"
    val quotient = StringBuilder()
    for (digit in a.magnitude) {
        checkCanceled()
        remainder = (remainder + digit).trimStart('0').ifEmpty { "0" }
        var count = 0
        while (compareMagnitude(remainder, b.magnitude) >= 0) {
            remainder = subtractMagnitude(remainder, b.magnitude)
            count++
        }
        quotient.append(('0'.code + count).toChar())
    }
    val magnitude = quotient.toString().trimStart('0').ifEmpty { "0" }
    val value = IntegerConstant(magnitude != "0" && a.negative != b.negative, magnitude)
    return ClickhouseConstantDivisionCheck(if (fits(value, result)) null else ClickhouseCompositionRule.INTEGER_DIVISION_OVERFLOW)
}
