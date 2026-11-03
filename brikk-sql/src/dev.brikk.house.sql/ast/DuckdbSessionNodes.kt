package dev.brikk.house.sql.ast

/** Keep the prepared body visible to parameter discovery without preparing or executing it. */
class DuckdbPrepare(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "expression" to true) }
}

class DuckdbExecute(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "expressions" to false) }
}

class DuckdbDeallocate(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true) }
}

class DuckdbResetVariable(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true) }
}
