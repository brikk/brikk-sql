package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AtTimeZone
import dev.brikk.house.sql.ast.Binary
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Div
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.IntDiv
import dev.brikk.house.sql.ast.Mod
import dev.brikk.house.sql.ast.Overlay
import dev.brikk.house.sql.ast.Sum
import dev.brikk.house.sql.ast.Try
import dev.brikk.house.sql.ast.intoExpr

/**
 * DuckDB's typing rules ahead of the shared typing metadata; null leaves the node to them.
 * [nativeAnalysis] adds the native 2.0 rules that SQLGlot's annotation contract does not have.
 */
internal fun Expression.duckdbExpressionType(nativeAnalysis: Boolean): Expression? {
    val temporalCast = if (nativeAnalysis) duckdbTemporalCastType() else null
    val json = duckdbJsonCall()
    return when {
        temporalCast != null -> temporalCast
        nativeAnalysis && this is Sum -> duckdbNativeSumType()
        nativeAnalysis && reviewedDuckdbFunctionName() != null -> reviewedDuckdbFunctionType() ?: DType.UNKNOWN.intoExpr()
        nativeAnalysis && this is AtTimeZone -> duckdbNativeTimeZoneType()
        nativeAnalysis && this is Try -> (thisArg as? Expression)?.type ?: DType.UNKNOWN.intoExpr()
        nativeAnalysis && (this is Div || this is IntDiv || this is Mod) -> (this as Binary).duckdbDivisionResultType()
        isDuckdbSetval() -> (if (duckdbSetvalArguments() != null) DType.BIGINT else DType.UNKNOWN).intoExpr()
        duckdbPathFunctionName() != null -> (if (duckdbPathArguments() != null) DType.VARCHAR else DType.UNKNOWN).intoExpr()
        this is Overlay || isDuckdbOverlayCall() ->
            (if (duckdbOverlayArguments() != null) DType.VARCHAR else DType.UNKNOWN).intoExpr()
        json != null -> (json.returnType ?: DType.UNKNOWN).intoExpr()
        isDuckdbVariantFunction() -> duckdbVariantReturnType() ?: DType.UNKNOWN.intoExpr()
        else -> null
    }
}
