package dev.brikk.house.sql.ast

class ClickhouseExplain(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "kind" to true, "settings" to false)
    /** ANALYZE runs the enclosed query. WHATIF can read data to estimate indexes, but does not run DDL. */
    val executesQuery get() = text("kind") == "ANALYZE"
}

class ClickhouseHypotheticalIndex(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "table" to true, "expression" to true,
        "type" to false, "granularity" to false, "exists" to false)
}

class ClickhouseHandler(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "kind" to true, "exists" to false,
        "cluster" to false, "protocol" to false, "url" to false, "match" to false,
        "methods" to false, "type" to false, "expression" to false)
}

class ClickhouseCreateToken(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("valid" to false, "valid_kind" to false,
        "grants" to false, "format" to false)
}

class ClickhouseGrant(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("expressions" to true, "this" to true)
}

class ClickhouseUserAuthentication(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "kind" to true, "exists" to false,
        "cluster" to false, "add" to false, "expressions" to true)
}

class ClickhouseAuthenticationMethod(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expression" to false,
        "valid" to false, "valid_kind" to false, "grants" to false)
}

/** Ordinary generation/serde is lossless. Diagnostic callers must use redactedClickhouseSql. */
class ClickhouseCredential(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true)
    override fun toString(): kotlin.String = "ClickhouseCredential([REDACTED])"
}

class ClickhouseAlterAction(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "kind" to true,
        "expression" to false, "expressions" to false, "exists" to false, "first" to false,
        "after" to false, "partition" to false)
}

class ClickhouseProjection(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expression" to true,
        "index" to false, "type" to false, "settings" to false)
}

class ClickhouseSystem(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to false, "kind" to true,
        "cluster" to false, "all" to false)
}

/** Refresh mode is distinct from APPEND of a complete result. */
class ClickhouseRefresh(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true)
}

internal fun registerClickhouseDdlNodes(entries: MutableMap<kotlin.String, ExpressionRegistry.Entry>) {
    fun add(name: kotlin.String, factory: () -> Expression) { entries[name] = ExpressionRegistry.Entry("brikk.clickhouse", factory) }
    add("ClickhouseExplain") { ClickhouseExplain() }
    add("ClickhouseHypotheticalIndex") { ClickhouseHypotheticalIndex() }
    add("ClickhouseHandler") { ClickhouseHandler() }
    add("ClickhouseCreateToken") { ClickhouseCreateToken() }
    add("ClickhouseGrant") { ClickhouseGrant() }
    add("ClickhouseUserAuthentication") { ClickhouseUserAuthentication() }
    add("ClickhouseAuthenticationMethod") { ClickhouseAuthenticationMethod() }
    add("ClickhouseCredential") { ClickhouseCredential() }
    add("ClickhouseAlterAction") { ClickhouseAlterAction() }
    add("ClickhouseProjection") { ClickhouseProjection() }
    add("ClickhouseSystem") { ClickhouseSystem() }
    add("ClickhouseRefresh") { ClickhouseRefresh() }
}
