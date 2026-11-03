package dev.brikk.house.sql.ast

/** Native DuckDB operator, whose partial/full semantics are chosen by the native session.
 * Unlike RegexpFullMatch/RegexpLike this is never lowered to an explicit regex function.
 * Negation belongs to the operator, especially with ANY/ALL, not to its quantified result.
 */
class DuckdbRegexOperator(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate {
    override val argTypes get() = TYPES
    private companion object {
        val TYPES = argTypesOf("this" to true, "expression" to true, "insensitive" to false, "negate" to false)
    }
}
