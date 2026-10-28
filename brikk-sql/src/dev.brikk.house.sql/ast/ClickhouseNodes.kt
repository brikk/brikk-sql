package dev.brikk.house.sql.ast

import kotlin.String

/** A ClickHouse streaming table read, not a table alias or a finite-table rewrite. */
class ClickhouseStream(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf(
        "this" to true, "bounded" to false, "unordered" to false, "cursor" to false,
        "watermark" to false, "expression" to false, "idle_timeout" to false,
    )
}

/** Cursor keys and unsigned integer leaves stay inspectable and survive AST serialization. */
class ClickhouseStreamCursor(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("expressions" to false)
}

/** A stream-order boundary, optionally combined with a positional count/offset and LIMIT BY. */
class ClickhouseLimitRange(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf(
        "this" to false, "expression" to false, "offset" to false, "after" to false,
        "until" to false, "all" to false, "limit_options" to false, "by" to false,
    )
}

class ClickhouseLimitBy(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expression" to true, "offset" to false)
}

class ClickhouseAtLocal(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true)
}

/** Zero-argument syntax and the two-argument timezone form. Pinned engines reject the zero-argument form. */
class ClickhouseYear(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("expressions" to false)
}

class ClickhouseCompoundInterval(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "unit" to true, "expression" to true)
}

/** Extra argument inside a remote/cluster table function, not a query-level SETTINGS clause. */
class ClickhouseFunctionSettings(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("expressions" to true)
}

/** QBit and MultiPoint remain distinct native types, not interchangeable arrays or opaque text. */
class ClickhouseNativeType(initArgs: Args = emptyMap()) : DataType(initArgs)

internal fun registerNativeClickhouseNodes(entries: kotlin.collections.MutableMap<String, ExpressionRegistry.Entry>) {
    entries["ClickhouseStream"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseStream() }
    entries["ClickhouseStreamCursor"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseStreamCursor() }
    entries["ClickhouseLimitRange"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseLimitRange() }
    entries["ClickhouseLimitBy"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseLimitBy() }
    entries["ClickhouseAtLocal"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseAtLocal() }
    entries["ClickhouseYear"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseYear() }
    entries["ClickhouseCompoundInterval"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseCompoundInterval() }
    entries["ClickhouseFunctionSettings"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseFunctionSettings() }
    entries["ClickhouseNativeType"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseNativeType() }
}
