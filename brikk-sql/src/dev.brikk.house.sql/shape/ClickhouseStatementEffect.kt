package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.*

/** Execution behavior, not an authorization or side-effect-free guarantee. */
enum class StatementEffect { PLANS_OR_INSPECTS, EXECUTES_QUERY, MODIFIES_STATE, UNKNOWN }

/** In particular EXPLAIN ANALYZE runs its child; WHATIF may read data to estimate indexes. */
val SqlFragment.statementEffect: StatementEffect get() = when (val node = ast) {
    is ClickhouseExplain -> if (node.executesQuery) StatementEffect.EXECUTES_QUERY else StatementEffect.PLANS_OR_INSPECTS
    is ClickhouseCreateToken, is ClickhouseUserAuthentication, is ClickhouseHandler,
    is ClickhouseHypotheticalIndex, is ClickhouseSystem, is Create, is Alter, is Drop,
    is Insert, is Update, is Delete, is Merge -> StatementEffect.MODIFIES_STATE
    is Query, is Values -> StatementEffect.EXECUTES_QUERY
    else -> StatementEffect.UNKNOWN
}
