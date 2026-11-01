package dev.brikk.house.sql.ast

/** DFParser RESET: ordered configuration-name components, not a query or a RESET ALL promise. */
class DatafusionReset(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("expressions" to true)
}

internal fun registerNativeDatafusionNodes(entries: MutableMap<kotlin.String, ExpressionRegistry.Entry>) {
    entries["DatafusionReset"] = ExpressionRegistry.Entry("brikk.datafusion") { DatafusionReset() }
}
