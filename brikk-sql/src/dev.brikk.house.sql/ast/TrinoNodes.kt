package dev.brikk.house.sql.ast

import kotlin.String

/** Brikk-native AST nodes for Trino syntax not modeled by sqlglot. */
class AtLocal(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

class MatchPredicate(initArgs: Args = emptyMap()) : Expression(initArgs), Predicate {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to false, "query" to true, "unique" to false, "match_type" to false,
        )
    }
}

class UniquePredicate(initArgs: Args = emptyMap()) : Expression(initArgs), SubqueryPredicate {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

/** SQL/JSON input representation, including the optional binary encoding. */
class TrinoJsonInput(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "encoding" to false)
}

class TrinoJsonConstructor(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = argTypesOf("this" to true)
    override val sqlNamesOverride get() = listOf("JSON")
}

class TrinoJsonSerialize(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = argTypesOf("this" to true, "returning" to false, "on_error" to false)
    override val sqlNamesOverride get() = listOf("JSON_SERIALIZE")
}

class TrinoJsonScalar(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = argTypesOf("this" to true)
    override val sqlNamesOverride get() = listOf("JSON_SCALAR")
}

/** SQL/JSON path invocation with representation and PASSING arguments. */
class TrinoJsonPathCall(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = argTypesOf(
        "this" to true, "kind" to true, "path" to true, "path_name" to false, "passing" to false,
        "returning" to false, "option" to false, "quote" to false, "on_condition" to false,
    )
    override val sqlNamesOverride get() = listOf(text("kind"))
}

/** Precisions are AST children, not text, so parametric interval types remain inspectable. */
class TrinoIntervalQualifier(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf(
        "this" to true, "leading_precision" to false, "fractional_precision" to false, "end" to false,
    )
}

class TrinoIntervalValue(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "qualifier" to true)
}

class TrinoMaterializedViewExecute(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf(
        "this" to true, "procedure" to true, "expressions" to false, "where" to false, "parentheses" to false,
    )
}

class TrinoSetAuthorization(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "kind" to true, "principal" to true)
}

class TrinoCreateSchema(initArgs: Args = emptyMap()) : Expression(initArgs), DDL {
    override val argTypes get() = argTypesOf("this" to true, "exists" to false, "principal" to true, "properties" to false)
}

/** Unlike DuckDB's NEAREST join modifier, Trino NEAREST is a relation primary. */
class TrinoNearest(initArgs: Args = emptyMap()) : Expression(initArgs), UDTF, Query {
    override val argTypes get() = argTypesOf(
        "this" to true, "where" to false, "match" to true, "alias" to false, "joins" to false,
    )
}

internal fun registerNativeTrinoNodes(
    entries: kotlin.collections.MutableMap<String, ExpressionRegistry.Entry>,
) {
    val module = "brikk.trino"
    entries["AtLocal"] = ExpressionRegistry.Entry(module) { AtLocal() }
    entries["MatchPredicate"] = ExpressionRegistry.Entry(module) { MatchPredicate() }
    entries["UniquePredicate"] = ExpressionRegistry.Entry(module) { UniquePredicate() }
    entries["TrinoJsonInput"] = ExpressionRegistry.Entry(module) { TrinoJsonInput() }
    entries["TrinoJsonConstructor"] = ExpressionRegistry.Entry(module) { TrinoJsonConstructor() }
    entries["TrinoJsonSerialize"] = ExpressionRegistry.Entry(module) { TrinoJsonSerialize() }
    entries["TrinoJsonScalar"] = ExpressionRegistry.Entry(module) { TrinoJsonScalar() }
    entries["TrinoJsonPathCall"] = ExpressionRegistry.Entry(module) { TrinoJsonPathCall() }
    entries["TrinoIntervalQualifier"] = ExpressionRegistry.Entry(module) { TrinoIntervalQualifier() }
    entries["TrinoIntervalValue"] = ExpressionRegistry.Entry(module) { TrinoIntervalValue() }
    entries["TrinoMaterializedViewExecute"] = ExpressionRegistry.Entry(module) { TrinoMaterializedViewExecute() }
    entries["TrinoNearest"] = ExpressionRegistry.Entry(module) { TrinoNearest() }
    entries["TrinoSetAuthorization"] = ExpressionRegistry.Entry(module) { TrinoSetAuthorization() }
    entries["TrinoCreateSchema"] = ExpressionRegistry.Entry(module) { TrinoCreateSchema() }
}
