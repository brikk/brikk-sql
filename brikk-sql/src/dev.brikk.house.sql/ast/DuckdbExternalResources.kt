package dev.brikk.house.sql.ast

/** Authored provider/type, name and option expressions; constructing a node never provisions anything. */
class DuckdbCreateExternalResource(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "alias" to false, "expressions" to false) }
}

class DuckdbRegisterExternalResource(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "alias" to false, "expression" to true) }
}

class DuckdbDestroyExternalResource(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true) }
}

class DuckdbShowExternalResources(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("all" to false) }
}

/** Temporary sources own provisioning; identifier references borrow registered resources. */
class DuckdbExternalResourceSource(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "temporary" to false, "expressions" to false) }
}

/** The source's options and these attachment options are deliberately separate bags. */
class DuckdbAttachExternalResource(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "alias" to true, "expressions" to false) }
}

class DuckdbConnectExternalResource(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "expressions" to false) }
}

internal fun Expression.isDuckdbExternalResourceStatement(): kotlin.Boolean =
    this is DuckdbCreateExternalResource || this is DuckdbRegisterExternalResource ||
    this is DuckdbDestroyExternalResource || this is DuckdbShowExternalResources ||
    this is DuckdbAttachExternalResource || this is DuckdbConnectExternalResource
