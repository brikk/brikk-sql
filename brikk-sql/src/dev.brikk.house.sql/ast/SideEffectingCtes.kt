package dev.brikk.house.sql.ast

/** Structural write/export CTEs, not an attempt to classify volatile SELECT functions. */
internal fun Expression.hasSideEffectingCte(): kotlin.Boolean = findAll<CTE>().any { it.thisArg is DML }
