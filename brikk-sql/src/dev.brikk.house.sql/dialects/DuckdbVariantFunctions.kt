package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode

internal fun Expression.isDuckdbVariantFunction(): kotlin.Boolean =
    this is Anonymous && (parent as? Dot)?.expressionArg !== this && name.uppercase() in DUCKDB_VARIANT_FUNCTIONS

/** Native result types, not the dynamically embedded type or a JSON-path translation. */
internal fun Expression.duckdbVariantReturnType(): DataType? {
    if (!isDuckdbVariantFunction()) return null
    val arguments = expressionsArg.filterIsInstance<Expression>()
    val name = name.uppercase()
    val scalar = when (name) {
        "VARIANT_EXTRACT", "VARIANT_CONTAINS", "VARIANT_GROUP_OBJECT" -> {
            if (arguments.size != 2) return null
            if (name == "VARIANT_CONTAINS") DType.BOOLEAN else DType.VARIANT
        }
        "VARIANT_TYPEOF", "VARIANT_NORMALIZE", "VARIANT_COMPARATOR", "VARIANT_BYTES_TO_VARIANT", "VARIANT_GROUP_ARRAY" -> {
            if (arguments.size != 1) return null
            when (name) { "VARIANT_TYPEOF" -> DType.VARCHAR; "VARIANT_COMPARATOR" -> DType.BINARY; else -> DType.VARIANT }
        }
        "VARIANT_TYPE", "VARIANT_EXTRACT_STRING", "VARIANT_KEYS", "VARIANT_EXISTS", "VARIANT_ARRAY_LENGTH" -> {
            if (arguments.size !in 1..2 || arguments.size == 1 && name in setOf("VARIANT_EXTRACT_STRING", "VARIANT_EXISTS")) return null
            when (name) { "VARIANT_KEYS", "VARIANT_TYPE", "VARIANT_EXTRACT_STRING" -> DType.VARCHAR
                "VARIANT_EXISTS" -> DType.BOOLEAN; else -> DType.UBIGINT }
        }
        else -> return null // In particular, variant_to_parquet_variant returns engine-bound ANY.
    }
    var result = DataType.build(scalar)
    if (name == "VARIANT_KEYS") result = DataType(args("this" to DType.ARRAY, "expressions" to listOf(result), "nested" to true))
    if (name in setOf("VARIANT_TYPE", "VARIANT_EXTRACT_STRING", "VARIANT_KEYS", "VARIANT_EXISTS", "VARIANT_ARRAY_LENGTH") && arguments.size == 2) {
        val path = arguments[1]
        val type = path.type as? DataType
        when {
            path is ArrayNode || type?.isType(DType.ARRAY) == true -> result = DataType(args("this" to DType.ARRAY, "expressions" to listOf(result), "nested" to true))
            path is Literal && path.isString || type?.thisArg in DataType.TEXT_TYPES -> Unit
            else -> return null
        }
    }
    return result
}

/** An explicit VARIANT expression proves that subsequent dot fields preserve their spelling. */
internal fun Expression.hasExplicitDuckdbVariantRoot(): kotlin.Boolean {
    var root = this
    while (root is Paren || root is Dot || root is Bracket) root = root.thisArg as? Expression ?: return false
    return (root is Cast && (root.args["to"] as? DataType)?.isType(DType.VARIANT) == true) ||
        root.isType(DType.VARIANT) || root.duckdbVariantReturnType()?.isType(DType.VARIANT) == true
}

private val DUCKDB_VARIANT_FUNCTIONS = setOf("VARIANT_ARRAY_LENGTH", "VARIANT_BYTES_TO_VARIANT", "VARIANT_COMPARATOR",
    "VARIANT_CONTAINS", "VARIANT_EXISTS", "VARIANT_EXTRACT", "VARIANT_EXTRACT_STRING", "VARIANT_GROUP_ARRAY",
    "VARIANT_GROUP_OBJECT", "VARIANT_KEYS", "VARIANT_NORMALIZE", "VARIANT_TO_PARQUET_VARIANT", "VARIANT_TYPE", "VARIANT_TYPEOF")
