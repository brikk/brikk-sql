package dev.brikk.house.sql.ast

import kotlin.String

/** Name selection, not a boolean LIKE expression applied to a row. */
class ClickhouseStar(initArgs: Args = emptyMap()) : Star(initArgs) {
    override val argTypes get() = super.argTypes + argTypesOf("like" to false)
}

class ClickhouseComposition(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expression" to true)
}

class ClickhouseImplicitLambda(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expressions" to true)
}

/** An argument reference retains authored spelling and positions without becoming a table column. */
class ClickhouseLambdaArgument(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "ordinal" to true)
}

class ClickhouseFunctionReference(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true)
}

class ClickhouseBuiltin(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expressions" to false)
}

class ClickhouseDatePart(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expression" to true)
}

class ClickhouseExtract(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = argTypesOf("this" to true, "expression" to true)
}

/** SQL LIKE escapes apply before regular-expression escaping. Match the complete column name. */
internal fun clickhouseStarPattern(star: Expression): Regex? {
    if (star !is ClickhouseStar) return null
    val pattern = (star.args["like"] ?: star.args["ilike"]) as? Literal ?: return null
    val out = StringBuilder()
    var escaped = false
    for (c in pattern.name) {
        if (escaped) { out.append(Regex.escape(c.toString())); escaped = false }
        else when (c) {
            '\\' -> escaped = true
            '%' -> out.append("[\\s\\S]*")
            '_' -> out.append("[\\s\\S]")
            else -> out.append(Regex.escape(c.toString()))
        }
    }
    if (escaped) out.append(Regex.escape("\\"))
    return if (star.args["ilike"] != null) Regex(out.toString(), RegexOption.IGNORE_CASE) else Regex(out.toString())
}

internal fun registerClickhouseAnalysisNodes(entries: MutableMap<String, ExpressionRegistry.Entry>) {
    fun add(name: String, factory: () -> Expression) { entries[name] = ExpressionRegistry.Entry("brikk.clickhouse", factory) }
    add("ClickhouseStar") { ClickhouseStar() }
    add("ClickhouseComposition") { ClickhouseComposition() }
    add("ClickhouseImplicitLambda") { ClickhouseImplicitLambda() }
    add("ClickhouseLambdaArgument") { ClickhouseLambdaArgument() }
    add("ClickhouseFunctionReference") { ClickhouseFunctionReference() }
    add("ClickhouseBuiltin") { ClickhouseBuiltin() }
    add("ClickhouseDatePart") { ClickhouseDatePart() }
    add("ClickhouseExtract") { ClickhouseExtract() }
}
