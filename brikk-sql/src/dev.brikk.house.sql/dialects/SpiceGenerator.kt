package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.String
import kotlin.reflect.KClass

class SpiceGenerator(pretty: kotlin.Boolean = false, sourceDialect: String? = null) : DatafusionGenerator(
    pretty = pretty, tokenizerConfig = SpiceDialect.TOKENIZER_CONFIG,
    overrides = SPICE_TRANSFORMS, sourceDialect = sourceDialect,
) {
    override val dialect: Dialect get() = Dialects.SPICE

    private fun utilitySql(e: Expression): String = when (e) {
        is SpiceReset -> "RESET ${sql(e, "this") }"
        is SpicePrepare -> "PREPARE ${sql(e, "this")}" +
            (if (e.args["typed"] == true) "(${expressions(e)})" else "") + " AS ${sql(e, "expression") }"
        is SpiceExecute -> "EXECUTE ${sql(e, "this")}" +
            (if (e.args["parenthesized"] == true) "(${expressions(e)})" else "")
        is SpiceShow -> {
            val kind = e.text("this")
            val target = sql(e, "target")
            "SHOW " + (if (e.args["full"] == true) "FULL " else "") +
                (if (kind == "SETTING") target else kind) +
                (if (kind == "COLUMNS") " FROM $target" else "") +
                (if (e.args["like"] != null) " LIKE ${sql(e, "like")}" else "")
        }
        is SpiceExplain -> {
            val options = e.expressionsArg.map { it as? PropertyEQ ?: throw UnsupportedError("Invalid Spice EXPLAIN option") }
                .joinToString(if (e.args["list"] == true) ", " else " ") { option ->
                sql(option, "this") + if (option.metaOrNull?.get("spice_implicit_option") == true) "" else " ${sql(option, "expression") }"
            }
            "EXPLAIN" + (if (options.isEmpty()) "" else if (e.args["list"] == true) " ($options)" else " $options") + " ${sql(e, "this") }"
        }
        else -> error("Unexpected Spice utility node")
    }

    companion object {
        private val SPICE_TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            put(BitwiseXor::class) { binary(it as Binary, "#") }
            put(Pow::class) {
                if (it.metaOrNull?.get("spice_exponent_operator") == true) binary(it as Binary, "^")
                else func("power", it.thisArg, it.expressionArg)
            }
            put(JSONExtract::class) { binary(it as Binary, "->") }
            put(JSONExtractScalar::class) { binary(it as Binary, "->>") }
            for (type in listOf(SpiceExplain::class, SpicePrepare::class, SpiceExecute::class, SpiceShow::class, SpiceReset::class)) {
                put(type) { (this as SpiceGenerator).utilitySql(it) }
            }
        }
    }
}
