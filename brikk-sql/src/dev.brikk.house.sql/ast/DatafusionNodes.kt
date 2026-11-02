package dev.brikk.house.sql.ast

/** DFParser RESET: ordered configuration-name components, not a query or a RESET ALL promise. */
class DatafusionReset(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("expressions" to true)
}

/** Options retain order: DFParser applies repeated ANALYZE options last-wins. */
class DatafusionExplain(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expressions" to false, "list" to false)
    val executesQuery: kotlin.Boolean get() = expressionsArg.filterIsInstance<PropertyEQ>()
        .lastOrNull { (it.thisArg as? Expression)?.name.equals("ANALYZE", true) }
        ?.expressionArg.let { value ->
            when (value) {
                is Boolean -> value.thisArg == true
                is Expression -> value.name.uppercase() in setOf("TRUE", "ON", "1")
                else -> false
            }
        }
}

class DatafusionPrepare(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expressions" to false, "expression" to true, "typed" to false)
}

/** Named SQL EXECUTE, separate from client JDBC parameter binding. */
class DatafusionExecute(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expressions" to false, "parenthesized" to false)
}

class DatafusionShow(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expressions" to false, "target" to false,
        "scope" to false, "expression" to false, "filter" to false, "full" to false, "extended" to false)
}

internal fun registerNativeDatafusionNodes(entries: MutableMap<kotlin.String, ExpressionRegistry.Entry>) {
    entries["DatafusionReset"] = ExpressionRegistry.Entry("brikk.datafusion") { DatafusionReset() }
    entries["DatafusionExplain"] = ExpressionRegistry.Entry("brikk.datafusion") { DatafusionExplain() }
    entries["DatafusionPrepare"] = ExpressionRegistry.Entry("brikk.datafusion") { DatafusionPrepare() }
    entries["DatafusionExecute"] = ExpressionRegistry.Entry("brikk.datafusion") { DatafusionExecute() }
    entries["DatafusionShow"] = ExpressionRegistry.Entry("brikk.datafusion") { DatafusionShow() }
}
