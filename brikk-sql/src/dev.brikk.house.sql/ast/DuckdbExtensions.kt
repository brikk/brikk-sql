package dev.brikk.house.sql.ast

/** Repository prefixes and public keys are authored literal data, never resolved by the parser. */
class DuckdbCreateExtensionRepository(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "prefix" to true, "expressions" to false, "replace" to false, "exists" to false) }
}

class DuckdbDropExtensionRepository(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "exists" to false) }
}

/** Kept separate from the published sqlglot Install shape, which simple INSTALL still uses. */
class DuckdbInstallExtension(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "from_" to false, "force" to false, "load" to false, "version" to false) }
}

/** A FROM identifier and a string are intentionally distinct, as is an explicit LOAD alias. */
class DuckdbLoadExtension(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = TYPES
    private companion object { val TYPES = argTypesOf("this" to true, "from_" to false, "alias" to false) }
}

internal fun Expression.isDuckdbExtensionManagement(): kotlin.Boolean = this is Install ||
    this is DuckdbCreateExtensionRepository || this is DuckdbDropExtensionRepository ||
    this is DuckdbInstallExtension || this is DuckdbLoadExtension
