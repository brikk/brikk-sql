package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*

/** Native 2.0 overload facts, separate from the historical SQLGlot annotation tables.
 * Keep the named nanosecond type losslessly.
 */
internal fun AtTimeZone.duckdbNativeTimeZoneType(): DataType {
    val input = (thisArg as? Expression)?.type as? DataType
    fun type(kind: DType) = DataType.build(kind)
    return when {
        input?.isType(DType.DATE, DType.TIMESTAMP_NS) == true ->
            DataType(args("this" to DType.USERDEFINED, "kind" to "TIMESTAMPTZ_NS"))
        input?.isType(DType.TIMESTAMP, DType.TIMESTAMPNTZ, DType.TIMESTAMP_S, DType.TIMESTAMP_MS) == true -> type(DType.TIMESTAMPTZ)
        input?.isType(DType.TIMESTAMPTZ) == true -> type(DType.TIMESTAMP)
        input?.thisArg == DType.USERDEFINED && input.text("kind").equals("TIMESTAMPTZ_NS", true) -> type(DType.TIMESTAMP_NS)
        input?.isType(DType.TIMETZ) == true -> type(DType.TIMETZ)
        else -> type(DType.UNKNOWN)
    }
}

internal fun Expression.hasDuckdbZoneOrCollation(): Boolean = walk().any {
    it is AtTimeZone || it is Collate || it is DataType && it.text("kind").equals("TIMESTAMPTZ_NS", true)
}
