package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Copy
import dev.brikk.house.sql.ast.CopyParameter
import dev.brikk.house.sql.ast.Expression

/** New engine-specific writer contracts have no certified backend-neutral rewrite. */
internal fun Expression.hasDuckdbParquetWriteContract():Boolean = findAll<Copy>().any { copy ->
    (copy.args["params"] as? List<*>).orEmpty().filterIsInstance<CopyParameter>().any {
        (it.thisArg as? Expression)?.name?.uppercase() in setOf("SHREDDING","DATA_PAGE_SIZE_LIMIT","WRITE_TIMESTAMP_AS_INT96","TIMESTAMP_IS_ADJUSTED_TO_UTC","GEOPARQUET_VERSION")
    }
}
