package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.metadata.CLICKHOUSE_26_8_34_13_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG

/**
 * CHSEM-002's hand-reviewed sidecar, not a replacement overload catalog. Only the
 * two exact native/source pins below are covered. No fallback to a nearby release.
 * A missing entry means unknown knowledge, not an invalid call or missing function.
 *
 * These are facts for a name already resolved as an unqualified builtin. They must
 * not override a local lambda, alias, column or user-defined function with that name.
 * Nothing here is wired into the type annotator, parser or editor diagnostics yet.
 */
internal fun clickhouseCallableFacts(name: String, engineVersion: String): ClickhouseCallableFacts? {
    val catalog = when (engineVersion) {
        "26.8.34.13" -> CLICKHOUSE_26_8_34_13_FUNCTION_CATALOG
        "26.10.2.103" -> CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG
        else -> return null
    }
    // Unlike FunctionCatalog.get, getAll respects each captured spelling's case policy.
    val canonical = catalog.getAll(name).singleOrNull()?.name ?: return null
    return clickhouseCallableFactsByName[canonical]
}

/** Native isVariadic/getNumberOfArguments declarations, NOT explicit call counts. */
internal sealed interface ClickhouseDesignatorArity {
    data class Fixed(val count: Int) : ClickhouseDesignatorArity
    data object Variadic : ClickhouseDesignatorArity
}

/**
 * Explicit-call counts within the corresponding supported non-NULL input rule,
 * not a universal function-level count bound or proof of valid types/values.
 * NULL/wrapped/unknown inputs are out of scope, including native early-NULL returns
 * that can bypass a variadic function's argument validation entirely.
 * For toString the second argument is only an option for DateTime/DateTime64;
 * a two-argument numeric call is not accepted merely because it fits the envelope.
 */
internal data class ClickhouseExplicitCallArity(
    val minimum: Int,
    /** null means no function-level upper bound, not an unknown upper bound. */
    val maximum: Int?,
    val secondArgumentRequiresDateTime: Boolean = false,
)

/**
 * Base-type rules. Only [exhaustiveForPlainTypes] rules can reject a known plain
 * type by exclusion. The other rules describe positive subsets whose complements
 * stay unknown. NULL, Nullable, LowCardinality, Dynamic, Variant and unknown types
 * are outside this slice. Constness and values are separate from a parameter's type.
 */
internal enum class ClickhouseCallableInputRule(val exhaustiveForPlainTypes: Boolean) {
    /** One String or FixedString(N); lower/upper preserve its exact base type. */
    STRING_OR_FIXED_STRING(true),
    /** String/FixedString input, optionally followed by a constant String character set. */
    TRIM_WITH_OPTIONAL_CONSTANT_STRING(true),
    /** Two of Int/UInt8,16,32,64 or Float32/64. Other native overloads remain unknown. */
    PRIMITIVE_NUMERIC_PAIR(false),
    /** One primitive number, String, FixedString, Date/Date32 or DateTime/DateTime64.
     * DateTime/DateTime64 can also take a String timezone, whose value still needs validation. */
    SCALAR_TO_STRING(false),
    /** Zero or more String/FixedString/primitive numeric arguments. All-array/map/tuple
     * dispatch and other serializable values are not covered by this scalar rule. */
    SCALAR_CONCAT(false),
}

/** Only applies within the corresponding positively supported input subset. */
internal enum class ClickhouseCallableResultRule {
    PRESERVE_STRING_INPUT,
    STRING,
    /** NumberTraits::ResultOfAdditionMultiplication for primitive inputs only:
     * signed if either is signed; floating if either is floating; double the largest
     * byte width below eight, otherwise keep it. Decimal/big-integer rules are not here. */
    PRIMITIVE_MULTIPLICATION_PROMOTION,
}

/** A recorded runtime restriction, separate from analyzer type acceptance. */
internal enum class ClickhouseCallableExecutionRestriction {
    /** URL extractors can expose a FixedString return type to toTypeName without
     * evaluating the function. Only actual execution rejects that column type. */
    FIXED_STRING_UNSUPPORTED,
}

internal data class ClickhouseCallableFacts(
    val canonicalName: String,
    val designatorArity: ClickhouseDesignatorArity,
    val explicitCallArity: ClickhouseExplicitCallArity,
    val inputRule: ClickhouseCallableInputRule,
    val resultRule: ClickhouseCallableResultRule,
    /** Stable source IDs in vendor/data/clickhouse-semantic/callable-sources.json. */
    val sourceIds: Set<String>,
    /** null means no extra execution restriction recorded, not guaranteed success. */
    val executionRestriction: ClickhouseCallableExecutionRestriction? = null,
)

private val clickhouseCallableFactsByName: Map<String, ClickhouseCallableFacts> = listOf(
    stringCallable("lower", ClickhouseCallableInputRule.STRING_OR_FIXED_STRING,
        ClickhouseCallableResultRule.PRESERVE_STRING_INPUT, "CHSEM-002-S02"),
    stringCallable("upper", ClickhouseCallableInputRule.STRING_OR_FIXED_STRING,
        ClickhouseCallableResultRule.PRESERVE_STRING_INPUT, "CHSEM-002-S03"),
    stringCallable("domainWithoutWWW", ClickhouseCallableInputRule.STRING_OR_FIXED_STRING,
        ClickhouseCallableResultRule.PRESERVE_STRING_INPUT, "CHSEM-002-S04", "CHSEM-002-S14",
        executionRestriction = ClickhouseCallableExecutionRestriction.FIXED_STRING_UNSUPPORTED),
    stringCallable("firstSignificantSubdomain", ClickhouseCallableInputRule.STRING_OR_FIXED_STRING,
        ClickhouseCallableResultRule.PRESERVE_STRING_INPUT, "CHSEM-002-S05", "CHSEM-002-S14",
        executionRestriction = ClickhouseCallableExecutionRestriction.FIXED_STRING_UNSUPPORTED),
    ClickhouseCallableFacts("trimBoth", ClickhouseDesignatorArity.Variadic,
        ClickhouseExplicitCallArity(1, 2), ClickhouseCallableInputRule.TRIM_WITH_OPTIONAL_CONSTANT_STRING,
        ClickhouseCallableResultRule.STRING, setOf("CHSEM-002-S06")),
    ClickhouseCallableFacts("multiply", ClickhouseDesignatorArity.Fixed(2),
        ClickhouseExplicitCallArity(2, 2), ClickhouseCallableInputRule.PRIMITIVE_NUMERIC_PAIR,
        ClickhouseCallableResultRule.PRIMITIVE_MULTIPLICATION_PROMOTION,
        setOf("CHSEM-002-S08", "CHSEM-002-S09", "CHSEM-002-S10")),
    ClickhouseCallableFacts("toString", ClickhouseDesignatorArity.Variadic,
        ClickhouseExplicitCallArity(1, 2, secondArgumentRequiresDateTime = true),
        ClickhouseCallableInputRule.SCALAR_TO_STRING, ClickhouseCallableResultRule.STRING,
        setOf("CHSEM-002-S11")),
    ClickhouseCallableFacts("concat", ClickhouseDesignatorArity.Variadic,
        ClickhouseExplicitCallArity(0, null), ClickhouseCallableInputRule.SCALAR_CONCAT,
        ClickhouseCallableResultRule.STRING, setOf("CHSEM-002-S07")),
).associateBy { it.canonicalName }

private fun stringCallable(
    name: String,
    input: ClickhouseCallableInputRule,
    result: ClickhouseCallableResultRule,
    vararg sourceIds: String,
    executionRestriction: ClickhouseCallableExecutionRestriction? = null,
) = ClickhouseCallableFacts(name, ClickhouseDesignatorArity.Fixed(1),
    ClickhouseExplicitCallArity(1, 1), input, result, sourceIds.toSet() + setOf("CHSEM-002-S01", "CHSEM-002-S12"),
    executionRestriction)
