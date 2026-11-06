package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*

private fun Expression.smallIntegralConstant(): Long? = when (this) {
    is Literal -> takeIf { isInt }?.name?.toLongOrNull()
    is Paren -> (thisArg as? Expression)?.smallIntegralConstant()
    is Neg -> (thisArg as? Expression)?.smallIntegralConstant()?.takeIf { it != Long.MIN_VALUE }?.let { -it }
    else -> null
}

private fun Expression.numericAtom(): Literal? = when (this) {
    is Literal -> takeIf { isNumber }
    is Paren, is Neg -> (thisArg as? Expression)?.numericAtom()
    else -> null
}

private fun Expression.provedIntegerOperand(): kotlin.Boolean {
    if (numericAtom() != null) return smallIntegralConstant()?.let { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() } == true
    val operandType = type as? DataType ?: return false
    return operandType.thisArg in DataType.INTEGER_TYPES && !operandType.isType(DType.BIT)
}

private fun Expression.explicitNullOperand(): kotlin.Boolean = unnest() is Null || (this as? Cast)?.thisArg is Null

/** A deliberately small constant proof. Never treat a parameter/column or lossy cast as
 * a known nonzero denominator, nor evaluate a host expression to approximate SQL binding.
 */
internal fun Expression.duckdbKnownNonZeroDivisor(): kotlin.Boolean = when (this) {
    is Paren -> (thisArg as? Expression)?.duckdbKnownNonZeroDivisor() == true
    is Neg -> (thisArg as? Expression)?.duckdbKnownNonZeroDivisor() == true
    is Literal -> isNumber && name.toDoubleOrNull()?.let { it.isFinite() && it != 0.0 } == true
    is Cast -> {
        val value = (thisArg as? Expression)?.smallIntegralConstant()
        val type = (args["to"] as? DataType)?.thisArg
        val limit = when (type) { DType.TINYINT ->
            127L
            DType.SMALLINT -> 32767L
            DType.INT -> 2147483647L
            DType.BIGINT, DType.INT128 -> 9007199254740991L
            else -> 0L
        }
        value != null && value != 0L && value in -limit..limit
    }
    else -> false
}

internal fun Expression.duckdbDivisionTranslationIssue(source: String?, target: String): String? {
    val nodes = walk().toList()
    if (source.equals("duckdb", true)) {
        if (nodes.any { it is SafeDivide }) return "DuckDB has no native SAFE_DIVIDE contract; do not replace an unknown call with a conditional rewrite"
        if (target != "duckdb" && nodes.any { it is IntDiv || it is Mod || it is Div && !it.right.duckdbKnownNonZeroDivisor() })
            return "DuckDB division/modulo zero policy, floating exceptional values and integer result widths have no verified cross-target translation"
    } else if (source != null && target == "duckdb") {
        if (nodes.any { it is SafeDivide || it is Div && it.args["safe"] == true })
            return "SAFE division overflow/NULL and one-time operand evaluation are not qualified for DuckDB"
        if (nodes.any { it is Div && it.args["typed"] == true && !it.right.duckdbKnownNonZeroDivisor() &&
                !it.right.explicitNullOperand() })
            return "Typed division with an unknown/zero divisor has no verified DuckDB session-policy translation"
        if (nodes.any { it is Div && it.args["typed"] == true &&
                !(it.left.provedIntegerOperand() && (it.right.provedIntegerOperand() || it.right.explicitNullOperand()) ||
                    it.left.explicitNullOperand() && it.right.provedIntegerOperand()) })
            return "Typed division requires proved integral operand types for exact DuckDB executable lowering"
    }
    return null
}

/** Native result facts only. Unproved mixed decimal/signedness cases remain UNKNOWN. */
internal fun Binary.duckdbDivisionResultType(): DataType {
    fun unknown() = DataType.build(DType.UNKNOWN)
    var l = left.type as? DataType ?: return unknown()
    var r = right.type as? DataType ?: return unknown()
    if (l.isType(DType.UNKNOWN) || r.isType(DType.UNKNOWN)) return unknown()
    // Parity annotates every bare integer atom INT, even wide native BIGINT/HUGEINT
    // values. Do not promote that approximation into an exact native arithmetic fact.
    if (listOf(left, right).any { operand -> operand.numericAtom()?.isInt == true &&
            operand.smallIntegralConstant()?.let { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() } != true }) return unknown()
    if (l.isType(DType.NULL) && r.isType(DType.NULL)) return DataType.build(if (this is Div) DType.DOUBLE else DType.BIGINT)
    if (l.isType(DType.NULL)) l = r
    if (r.isType(DType.NULL)) r = l
    val numeric = (DataType.INTEGER_TYPES - DType.BIT) + setOf(DType.FLOAT, DType.DOUBLE, DType.DECIMAL)
    if (l.thisArg !in numeric || r.thisArg !in numeric) return unknown()
    // The parity literal annotator calls decimal-text atoms DOUBLE. Do not claim an
    // exact native decimal modulo precision from that approximate input fact.
    val decimalAtom = listOf(left, right).any { it.numericAtom()?.let { atom -> !atom.isInt && !atom.name.contains('e', true) } == true }
    if (decimalAtom && (this is Mod || l.isType(DType.FLOAT) || r.isType(DType.FLOAT))) return unknown()
    if (l.isType(DType.DOUBLE) || r.isType(DType.DOUBLE)) return DataType.build(DType.DOUBLE)
    if (l.isType(DType.FLOAT) || r.isType(DType.FLOAT)) return DataType.build(DType.FLOAT)
    if (this is Div || this is IntDiv && (l.isType(DType.DECIMAL) || r.isType(DType.DECIMAL))) return DataType.build(DType.DOUBLE)
    if (l == r) return l.copy() as DataType
    val signed = listOf(DType.TINYINT, DType.SMALLINT, DType.INT, DType.BIGINT, DType.INT128)
    val a = signed.indexOf(l.thisArg)
    val b = signed.indexOf(r.thisArg)
    return if (a >= 0 && b >= 0) DataType.build(signed[maxOf(a, b)]) else unknown()
}
