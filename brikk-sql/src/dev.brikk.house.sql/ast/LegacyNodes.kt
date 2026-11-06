package dev.brikk.house.sql.ast

/** Published pre-30.29 node. Keep its class, constructors, schema and serde name. */
class SortArray(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "asc" to false, "nulls_first" to false)
    }
}
