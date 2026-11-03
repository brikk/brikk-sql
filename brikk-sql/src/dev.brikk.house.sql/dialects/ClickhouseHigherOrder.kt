package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import kotlin.String
import kotlin.collections.Set

// system.functions.higher_order, pinned 26.10.2.103. These are argument-binding contexts,
// not a claim that each function's result type or cross-engine semantics is known.
internal val clickhouseHigherOrderNames = setOf(
    "arrayAll", "arrayAvg", "arrayBottomK", "arrayCompact", "arrayCount", "arrayCumSum",
    "arrayCumSumNonNegative", "arrayDifference", "arrayExists", "arrayFill", "arrayFilter",
    "arrayFirst", "arrayFirstIndex", "arrayFirstOrNull", "arrayFold", "arrayLast", "arrayLastIndex",
    "arrayLastOrNull", "arrayMap", "arrayMax", "arrayMaxIndex", "arrayMin", "arrayMinIndex",
    "arrayPackBitGroupsToFixedString", "arrayPackBitGroupsToString", "arrayPackBitGroupsToUInt64",
    "arrayPackBitsToFixedString", "arrayPackBitsToString", "arrayPackBitsToUInt64", "arrayPartialReverseSort",
    "arrayPartialSort", "arrayProduct", "arrayReverseFill", "arrayReverseSort", "arrayReverseSplit",
    "arraySort", "arraySplit", "arraySum", "arrayTopK", "mapAll", "mapApply", "mapExists", "mapFilter",
    "mapPartialReverseSort", "mapPartialSort", "mapReverseSort", "mapSort",
)

/** Lift only the first argument of a known higher-order call, never a free underscore column. */
internal fun clickhouseLiftLambda(input: Expression): Expression {
    if (input is Lambda || input is ClickhouseImplicitLambda || input is ClickhouseComposition) return input
    if (input is Paren) {
        (input.thisArg as? Expression)?.let { input.set("this", clickhouseLiftLambda(it)) }
        return input
    }
    if (input is Column && input.args["table"] == null && !input.name.matches(Regex("_(?:[1-9][0-9]*)?"))) {
        return ClickhouseFunctionReference(args("this" to input.thisArg))
    }
    var anonymous = 0
    val ordinals = mutableSetOf<Int>()
    fun rewrite(node: Expression, bound: Set<String> = emptySet()): Expression {
        if (node is Select || node is Subquery || node is SetOperation ||
            node is ClickhouseImplicitLambda || node is ClickhouseComposition) return node
        if (node is Lambda) {
            val local = bound + node.expressionsArg.filterIsInstance<Identifier>().map { it.name }
            (node.thisArg as? Expression)?.let { node.set("this", rewrite(it, local)) }
            return node
        }
        if (node is Column) {
            val first = (node.args["table"] ?: node.thisArg) as? Identifier
            val name = first?.name.orEmpty()
            if (name !in bound && name.matches(Regex("_(?:[1-9][0-9]*)?"))) {
                val ordinal = if (name == "_") ++anonymous else name.drop(1).toIntOrNull() ?: 129
                // Keep invalid native arities as syntax, but never allocate an unbounded parameter list.
                ordinals.add(ordinal.coerceAtMost(129))
                val argument = ClickhouseLambdaArgument(args("this" to first, "ordinal" to ordinal))
                return if (node.args["table"] == null) argument else Dot(args("this" to argument, "expression" to node.thisArg))
            }
        }
        for (child in node.iterExpressions().toList()) {
            val value = rewrite(child, bound)
            if (value !== child) child.replace(value)
        }
        return node
    }
    val body = rewrite(input)
    if (ordinals.isEmpty()) return input
    return ClickhouseImplicitLambda(args("this" to body,
        "expressions" to (1..ordinals.max()).map { Identifier(args("this" to "_$it", "quoted" to false)) }))
}

internal fun clickhouseCompose(values: Args): Expression {
    fun operand(key: String): Expression? = (values[key] as? Expression)?.let(::clickhouseLiftLambda)
    return ClickhouseComposition(args("this" to operand("this"), "expression" to operand("expression")))
}
