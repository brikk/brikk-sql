package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.*
import kotlin.String

/** Bounded native execution controls in round10; these describe Brikk lowering, not native pipe grammar. */
val DATAFUSION_PIPE_STAGES: Set<String> = setOf("SELECT", "WHERE", "EXTEND", "AS", "AGGREGATE", "DISTINCT", "ORDER BY", "LIMIT", "OFFSET", "WINDOW")
val SPICE_PIPE_STAGES: Set<String> = DATAFUSION_PIPE_STAGES.toSet()

internal fun datafusionPipeRefusal(ast: Expression, dialect: String): String? {
    if (dialect !in setOf("datafusion", "spice")) return null
    for (pipe in ast.walk().filterIsInstance<PipeQuery>()) {
        for (stage in pipe.expressionsArg.filterIsInstance<Expression>()) {
            if (stage !is PipeSelect && stage !is PipeWhere && stage !is PipeExtend && stage !is PipeAs &&
                stage !is PipeAggregate && stage !is PipeDistinct && stage !is PipeOrderBy && stage !is PipeLimit &&
                stage !is PipeOffset && stage !is PipeWindow)
                return "${stage::class.simpleName} has no qualified Brikk PIPE lowering for $dialect"
        }
    }
    return null
}
