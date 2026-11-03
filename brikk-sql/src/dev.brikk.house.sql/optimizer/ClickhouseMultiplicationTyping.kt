package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.metadata.CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG
import kotlin.String
import kotlin.collections.List

internal data class ClickhouseMultiplicationCall(val operands: List<Expression>, val name: String? = null)

/** Only the operator or the resolved unqualified builtin, never a member/UDF by name alone. */
internal fun clickhouseMultiplicationCall(node: Expression): ClickhouseMultiplicationCall? {
    if (node.parent is Dot) return null
    val authored = node.metaOrNull?.get("clickhouse_name") as? String
    if (node is Mul && authored == null) return ClickhouseMultiplicationCall(listOf(node.left, node.right))
    val name = authored ?: (node as? Anonymous)?.name ?: return null
    if (CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG.getAll(name).none { it.name == "multiply" }) return null
    val operands = if (node is Mul) listOf(node.left, node.right)
        else node.expressionsArg.filterIsInstance<Expression>().takeIf { it.size == node.expressionsArg.size } ?: return null
    return if (operands.size == 2) ClickhouseMultiplicationCall(operands, name) else null
}

/** multiply.cpp uses the same NumberTraits::ResultOfAdditionMultiplication as plus.cpp. */
internal fun clickhousePrimitiveMultiplicationResult(left: DataType?, right: DataType?): DataType? =
    clickhouseIntegerAddSubResult(left, right, subtract = false) ?: clickhouseFloatingAddSubResult(left, right)
