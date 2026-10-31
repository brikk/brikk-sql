package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.clickhouseBooleanSetting
import dev.brikk.house.sql.metadata.CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List

internal data class ClickhouseDivisionCall(val operands: List<Expression>, val integerMode: Boolean?, val name: String? = null)

/** `/` is an operator; named functions must first resolve through the exact builtin catalog. */
internal fun clickhouseDivisionCall(node: Expression): ClickhouseDivisionCall? {
    if (node is Div) return ClickhouseDivisionCall(listOf(node.left, node.right),
        clickhouseBooleanSetting(node, "sql_compatible_division_for_integers"))
    if (node !is Anonymous) return null
    val name = node.metaOrNull?.get("clickhouse_name") as? String ?: node.name
    val builtin = CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG.getAll(name).firstOrNull { it.name in setOf("divide", "divideExact") } ?: return null
    val operands = node.expressionsArg.filterIsInstance<Expression>()
    if (operands.size != 2 || operands.size != node.expressionsArg.size) return null
    return ClickhouseDivisionCall(operands,
        if (builtin.name == "divideExact") true else clickhouseBooleanSetting(node, "sql_compatible_division_for_integers"), name)
}

/** NumberTraits::ResultOfIntegerDivision, plus the legacy floating delegate. */
internal fun clickhouseDivisionResult(left: DataType?, right: DataType?, integerMode: Boolean?): DataType? {
    if (left == null || right == null || left.args["nullable"] !is Boolean || right.args["nullable"] !is Boolean) return null
    val signed = listOf(DType.TINYINT, DType.SMALLINT, DType.INT, DType.BIGINT, DType.INT128, DType.INT256)
    val unsigned = listOf(DType.UTINYINT, DType.USMALLINT, DType.UINT, DType.UBIGINT, DType.UINT128, DType.UINT256)
    val integers = signed + unsigned
    val floats = setOf(DType.FLOAT, DType.DOUBLE)
    val a = left.thisArg
    val b = right.thisArg
    val kind = when {
        a in integers && b in integers && integerMode == true -> {
            val width = signed.indexOf(a).takeIf { it >= 0 } ?: unsigned.indexOf(a)
            if (a in signed || b in signed) signed[width] else unsigned[width]
        }
        a in integers && b in integers && integerMode == false -> DType.DOUBLE
        a in integers + floats && b in integers + floats && (a in floats || b in floats) -> DType.DOUBLE
        else -> return null
    }
    return clickhousePlainType(kind).also { it.set("nullable", left.args["nullable"] == true || right.args["nullable"] == true) }
}
