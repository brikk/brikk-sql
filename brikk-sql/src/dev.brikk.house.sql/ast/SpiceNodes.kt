package dev.brikk.house.sql.ast

/** Structured utility statements for the pinned Spice runtime profile. */
class SpiceExplain(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expressions" to false, "list" to false)
    val executesQuery: kotlin.Boolean get() = expressionsArg.filterIsInstance<PropertyEQ>().any {
        it.thisArg.let { name -> (name as? Expression)?.name }.equals("ANALYZE", true) &&
            when (val value = it.expressionArg) {
                is Boolean -> value.thisArg == true
                is Expression -> value.name.uppercase() in setOf("TRUE", "ON", "1")
                else -> false
            }
    }
}

class SpicePrepare(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expressions" to false, "expression" to true, "typed" to false)
}

class SpiceExecute(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expressions" to false, "parenthesized" to false)
}

class SpiceShow(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "target" to false, "like" to false, "full" to false)
}

class SpiceReset(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true)
}

internal fun registerNativeSpiceNodes(entries: MutableMap<kotlin.String, ExpressionRegistry.Entry>) {
    entries["SpiceExplain"] = ExpressionRegistry.Entry("brikk.spice") { SpiceExplain() }
    entries["SpicePrepare"] = ExpressionRegistry.Entry("brikk.spice") { SpicePrepare() }
    entries["SpiceExecute"] = ExpressionRegistry.Entry("brikk.spice") { SpiceExecute() }
    entries["SpiceShow"] = ExpressionRegistry.Entry("brikk.spice") { SpiceShow() }
    entries["SpiceReset"] = ExpressionRegistry.Entry("brikk.spice") { SpiceReset() }
}
