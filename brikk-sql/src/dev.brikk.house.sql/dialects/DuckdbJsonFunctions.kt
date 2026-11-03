package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.JSONRemove
import dev.brikk.house.sql.ast.JSONSet
import dev.brikk.house.sql.ast.JSONStripNulls
import dev.brikk.house.sql.ast.Kwarg
import dev.brikk.house.sql.ast.PropertyEQ

/** Pinned DuckDB 2.0 builtin signatures, not same-name cross-dialect identities. */
internal class DuckdbJsonCall(val name: String, val arguments: List<Expression>, private val unsupportedOptions: Boolean) {
    private val parameters: List<String> = when (name) {
        "JSON_SET", "JSON_INSERT", "JSON_REPLACE" -> listOf("json", "path", "value")
        "JSON_REMOVE" -> listOf("json", "path")
        "JSON_MERGE_PATCH_DIFF" -> listOf("old", "new")
        "JSON_DEEP_MERGE" -> listOf("json1", "json2")
        else -> listOf("json")
    }

    /** Ordered by parameter role; binding still decides value types and path/JSON validity. */
    val boundArguments: List<Expression>? get() {
        if (unsupportedOptions) return null
        if (arguments.none { it is PropertyEQ || it is Kwarg }) {
            return arguments.takeIf { if (name == "JSON_DEEP_MERGE") it.size >= 2 else it.size == parameters.size }
        }
        // Named fixed arguments may be reordered. Variadic named/mixed forms whose binding
        // is not modeled stay unknown, rather than guessing which argument is the final patch.
        if (arguments.size != parameters.size) return null
        val values = arrayOfNulls<Expression>(parameters.size)
        var named = false
        for ((index, argument) in arguments.withIndex()) {
            val position = if (argument is PropertyEQ || argument is Kwarg) {
                named = true
                parameters.indexOf((argument.thisArg as? Expression)?.name?.lowercase())
            } else {
                if (named) return null
                index
            }
            if (position !in values.indices || values[position] != null) return null
            values[position] = if (argument is PropertyEQ || argument is Kwarg) argument.expressionArg as? Expression else argument
        }
        return values.filterNotNull().takeIf { it.size == parameters.size }
    }

    val returnType: DType? get() = boundArguments?.let { if (name == "JSON_NORMALIZE") DType.VARCHAR else DType.JSON }
}

/** Existing structured/raw-call nodes retain every authored JSON/path value without reinterpretation. */
internal fun Expression.duckdbJsonCall(): DuckdbJsonCall? {
    if ((parent as? Dot)?.expressionArg === this) return null // A schema-owned call is not a builtin contract.
    val name = when (this) {
        is JSONSet -> "JSON_SET"
        is JSONRemove -> "JSON_REMOVE"
        is JSONStripNulls -> "JSON_STRIP_NULLS"
        is Anonymous -> name.uppercase()
        else -> return null
    }
    if (name !in DUCKDB_JSON_FUNCTIONS) return null
    val arguments = if (this is Anonymous) expressionsArg.filterIsInstance<Expression>()
        else iterExpressions().toList()
    val unsupportedOptions = this is JSONStripNulls && listOf("expression", "include_arrays", "remove_empty").any { args[it] != null }
    return DuckdbJsonCall(name, arguments, unsupportedOptions)
}

private val DUCKDB_JSON_FUNCTIONS = setOf("JSON_SET", "JSON_INSERT", "JSON_REPLACE", "JSON_REMOVE",
    "JSON_DEEP_MERGE", "JSON_MERGE_PATCH_DIFF", "JSON_NORMALIZE", "JSON_STRIP_NULLS")
