package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Factorial
import dev.brikk.house.sql.ast.Overlay
import dev.brikk.house.sql.ast.Try
import dev.brikk.house.sql.optimizer.NullabilityRules
import dev.brikk.house.sql.scope.Nullability

/** DuckDB's function nullability ahead of the catalog profiles; null leaves the call to them. */
internal fun Expression.duckdbFunctionNullability(rules: NullabilityRules): Nullability? {
    fun verdict(nullable: Boolean?): Nullability = when (nullable) {
        true -> Nullability.NULLABLE
        false -> Nullability.NON_NULL
        null -> Nullability.UNKNOWN
    }
    if (this is Try) return Nullability.NULLABLE
    if (this is Factorial) return verdict(rules.operatorRule(iterExpressions().toList()))
    duckdbGeoJsonScalarArguments()?.let { return verdict(rules.operatorRule(it)) }
    if (duckdbRegexName() != null) {
        if (duckdbRegexReturnType() == null) return Nullability.UNKNOWN
        val arguments = duckdbRegexArguments() ?: return Nullability.UNKNOWN
        // Scalar extraction's NULL group yields '', not SQL NULL. Options bind
        // as non-null constants or error; they do not add nullable successful rows.
        val count = if (duckdbRegexName() == "REGEXP_EXTRACT" || duckdbRegexName() == "REGEXP_EXTRACT_ALL" &&
            arguments.getOrNull(2) is dev.brikk.house.sql.ast.Array) 2 else 3
        return verdict(rules.operatorRule(arguments.take(count)))
    }
    if (isDuckdbSetval()) {
        val arguments = duckdbSetvalArguments() ?: return Nullability.UNKNOWN
        val verdicts = arguments.map { if (it is dev.brikk.house.sql.ast.Boolean) false else rules.verdict(it) }
        return verdict(when {
            verdicts.any { it == true } -> true
            verdicts.all { it == false } -> false
            else -> null
        })
    }
    val path = duckdbPathFunctionName()
    if (path != null) {
        val arguments = duckdbPathArguments() ?: return Nullability.UNKNOWN
        return if (path == "HIVE_PARTITION_COMPONENT") Nullability.NON_NULL else verdict(rules.operatorRule(arguments))
    }
    if (isDuckdbOverlayCall() || this is Overlay) return verdict(duckdbOverlayArguments()?.let(rules::operatorRule))
    val json = duckdbJsonCall() ?: return null
    val arguments = json.boundArguments ?: return Nullability.UNKNOWN
    return verdict(when (json.name) {
        // Removing the root yields SQL NULL even for a non-null document/path.
        "JSON_REMOVE" -> true
        // Each patch replaces a SQL NULL intermediate; only the final patch's
        // SQL nullability determines whether a successful merge has a document.
        "JSON_DEEP_MERGE", "JSON_MERGE_PATCH_DIFF" -> rules.verdict(arguments.last())
        else -> rules.operatorRule(arguments)
    })
}
