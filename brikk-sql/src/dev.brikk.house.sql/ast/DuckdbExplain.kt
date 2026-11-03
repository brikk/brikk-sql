package dev.brikk.house.sql.ast

/** Authored options and child statement, not an instruction for analysis to prepare or execute it. */
class DuckdbExplain(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "analyze" to false, "expressions" to false) }
}

/** ANALYZE arguments are retained even though native 2.0 ignores their values. */
class DuckdbExplainOption(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "expression" to false) }
}
