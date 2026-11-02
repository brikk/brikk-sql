package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import kotlin.Boolean

/** Float32/64 force nextSize(max(sizeof(A), sizeof(B))) to eight bytes for admitted pairs. */
internal fun clickhouseFloatingAddSubResult(left: DataType?, right: DataType?): DataType? {
    if (left == null || right == null || left.args["nullable"] !is Boolean || right.args["nullable"] !is Boolean) return null
    fun floating(type: DataType) = type.thisArg == DType.FLOAT || type.thisArg == DType.DOUBLE
    fun supported(type: DataType) = floating(type) || clickhouseIntegerShape(type)?.first?.let { it <= 64 } == true
    if (!floating(left) && !floating(right) || !supported(left) || !supported(right)) return null
    return clickhousePlainType(DType.DOUBLE).also { it.set("nullable", left.args["nullable"] == true || right.args["nullable"] == true) }
}
