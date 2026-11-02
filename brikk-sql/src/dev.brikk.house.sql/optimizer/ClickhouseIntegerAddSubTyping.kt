package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.metadata.CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List

internal data class ClickhouseIntegerAddSubCall(val operands: List<Expression>, val subtract: Boolean, val name: String? = null)

/** Shared integer/floating operator resolver. Named calls require the exact builtin catalog. */
internal fun clickhouseIntegerAddSubCall(node: Expression): ClickhouseIntegerAddSubCall? {
    if (node is Add) return ClickhouseIntegerAddSubCall(listOf(node.left, node.right), false)
    if (node is Sub) return ClickhouseIntegerAddSubCall(listOf(node.left, node.right), true)
    if (node !is Anonymous) return null
    if (node.parent is Dot) return null // A qualified/member call is not a proved unqualified builtin.
    val name = node.metaOrNull?.get("clickhouse_name") as? String ?: node.name
    val builtin = CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG.getAll(name).firstOrNull { it.name in setOf("plus", "minus") } ?: return null
    val operands = node.expressionsArg.filterIsInstance<Expression>()
    if (operands.size != 2 || operands.size != node.expressionsArg.size) return null
    return ClickhouseIntegerAddSubCall(operands, builtin.name == "minus", name)
}

/** NumberTraits nextSize stops widening at 64 bits, including already-wide integer types. */
internal fun clickhouseIntegerAddSubResult(left: DataType?, right: DataType?, subtract: Boolean): DataType? {
    if (left == null || right == null || left.args["nullable"] !is Boolean || right.args["nullable"] !is Boolean) return null
    val a = clickhouseIntegerShape(left) ?: return null
    val b = clickhouseIntegerShape(right) ?: return null
    val maximum = maxOf(a.first, b.first)
    val width = if (maximum < 64) maximum * 2 else maximum
    val signed = subtract || a.second || b.second
    val kind = when (width) {
        16 -> if (signed) DType.SMALLINT else DType.USMALLINT
        32 -> if (signed) DType.INT else DType.UINT
        64 -> if (signed) DType.BIGINT else DType.UBIGINT
        128 -> if (signed) DType.INT128 else DType.UINT128
        256 -> if (signed) DType.INT256 else DType.UINT256
        else -> return null
    }
    return clickhousePlainType(kind).also { it.set("nullable", left.args["nullable"] == true || right.args["nullable"] == true) }
}
