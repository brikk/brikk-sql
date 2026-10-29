package dev.brikk.house.sql.ast

/** DuckDB's CREATE [SECURE] RECURSIVE VIEW modifier, not a recursive CTE rewrite. */
class DuckdbRecursiveViewProperty(initArgs: Args = emptyMap()) : Property(initArgs) {
    override val argTypes get() = argTypesOf()
}

internal fun Expression.isSecureViewDefinition(): kotlin.Boolean = this is Create &&
    text("kind").equals("VIEW", ignoreCase = true) &&
    (args["properties"] as? Properties)?.expressionsArg?.any { it is SecureProperty } == true

internal fun Expression.isDuckdbRecursiveViewDefinition(): kotlin.Boolean = this is Create &&
    (args["properties"] as? Properties)?.expressionsArg?.any { it is DuckdbRecursiveViewProperty } == true
