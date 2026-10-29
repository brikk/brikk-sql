package dev.brikk.house.sql.ast

/** A keyed recursive state is not a conventional SELECT/UNION relation to rewrite. */
internal fun Expression.hasKeyedCte(): kotlin.Boolean = findAll<CTE>().any {
    !(it.args["key_expressions"] as? kotlin.collections.List<*>).isNullOrEmpty()
}

internal fun Expression.hasKeyedAggregatePayload(): kotlin.Boolean = findAll<CTE>().any { cte ->
    (cte.args["key_expressions"] as? kotlin.collections.List<*>).orEmpty().any { it is Expression && it !is Identifier && it !is Column }
}

/** Only inside this CTE's recursive member, never an anchor, sibling, or outer query. */
internal fun Table.isRecurringCteReference(): kotlin.Boolean {
    if (catalog.isNotEmpty() || !db.equals("recurring", ignoreCase = true)) return false
    for (owner in generateSequence(parent) { it.parent }.filterIsInstance<CTE>()) {
        val with = owner.parent as? With ?: continue
        if (!with.recursive || !owner.alias.equals(name, ignoreCase = true)) continue
        val union = (owner.thisArg as? Expression)?.unnest() as? Union ?: continue
        if (generateSequence(parent) { it.parent }.any { it === union.right }) return true
    }
    return false
}
