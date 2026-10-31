package dev.brikk.house.sql.ast

/** DuckDB 2.0 X/B literals are BLOB/BIT values, never integer or prefixed text literals.
 * Keep hex digits and leading zero bits as text; a host integer cannot preserve them.
 */
class DuckdbBinaryLiteral(initArgs: Args = emptyMap()) : Expression(initArgs), Condition {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "kind" to true) }
}
