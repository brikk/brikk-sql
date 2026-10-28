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

internal fun registerNativeClickhouseNodes(entries: kotlin.collections.MutableMap<String, ExpressionRegistry.Entry>) {
    entries["ClickhouseStream"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseStream() }
    entries["ClickhouseStreamCursor"] = ExpressionRegistry.Entry("brikk.clickhouse") { ClickhouseStreamCursor() }
}
