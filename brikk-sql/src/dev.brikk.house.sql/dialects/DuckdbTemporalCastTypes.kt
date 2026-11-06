package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*

/** Semantic cast result type only. The authored destination AST keeps its precision. */
internal fun Expression.duckdbTemporalCastType(): DataType? {
    if (this !is Cast && this !is TryCast) return null
    val target = args["to"] as? DataType ?: return null
    if (target.expressionsArg.isEmpty()) return null
    if (target.isType(DType.TIMESTAMP, DType.TIMESTAMPNTZ)) {
        val parameter = target.expressionsArg.singleOrNull() as? DataTypeParam
        val literal = parameter?.thisArg as? Literal
        val precision = literal?.takeIf { it.isInt }?.name?.toIntOrNull()
        val result = when (precision) {
            0 -> DType.TIMESTAMP_S
            in 1..3 -> DType.TIMESTAMP_MS
            in 4..6 -> DType.TIMESTAMP
            in 7..10 -> DType.TIMESTAMP_NS
            else -> DType.UNKNOWN
        }
        return DataType.build(result)
    }
    val temporal = target.isType(DType.DATE, DType.TIME, DType.TIME_NS, DType.TIMETZ, DType.TIMESTAMPTZ, DType.TIMESTAMP_S, DType.TIMESTAMP_MS, DType.TIMESTAMP_NS) ||
        target.thisArg == DType.USERDEFINED && target.text("kind").uppercase() in setOf("TIME_NS", "TIMESTAMPTZ_NS")
    return if (temporal) DataType.build(DType.UNKNOWN) else null
}
