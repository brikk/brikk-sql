package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.ArrayFilter
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Transform

/** CHSEM-008, source-pinned FunctionArrayMapped and mapApply adapter contracts. */
internal enum class ClickhouseHigherOrderKind(val function: String, val predicate: Boolean = false, val optionalLambda: Boolean = false) {
    MAP("arrayMap"), FILTER("arrayFilter", predicate = true),
    EXISTS("arrayExists", predicate = true, optionalLambda = true),
    ALL("arrayAll", predicate = true, optionalLambda = true),
    SORT("arraySort", optionalLambda = true), APPLY("mapApply"),
}

internal data class ClickhouseHigherOrderCall(
    val argument: Expression?,
    val inputs: List<Expression>,
    val kind: ClickhouseHigherOrderKind,
    val name: String,
)

internal fun clickhouseHigherOrderCall(node: Expression): ClickhouseHigherOrderCall? {
    val original = node.metaOrNull?.get("clickhouse_name") as? String
    if (node is Transform || node is ArrayFilter) {
        val kind = if (node is Transform) ClickhouseHigherOrderKind.MAP else ClickhouseHigherOrderKind.FILTER
        return ClickhouseHigherOrderCall(node.args["expression"] as? Expression,
            listOfNotNull(node.thisArg as? Expression), kind, original ?: kind.function)
    }
    if (node !is Anonymous) return null
    val kind = ClickhouseHigherOrderKind.entries.firstOrNull { it.function == node.name } ?: return null
    val values = node.expressionsArg.filterIsInstance<Expression>()
    if (values.size != node.expressionsArg.size) return null
    return if (kind.optionalLambda && values.size == 1) ClickhouseHigherOrderCall(null, values, kind, original ?: node.name)
    else ClickhouseHigherOrderCall(values.firstOrNull(), values.drop(1), kind, original ?: node.name)
}
