package dev.brikk.house.sql.ast

/** Bounded SQLGlot query.ReplaceColumns port, SG29-015/#8633. */
class ReplaceColumns(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to true)
    }
}
