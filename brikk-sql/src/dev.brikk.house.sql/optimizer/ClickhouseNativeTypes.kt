package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map

/** Exact tagged FieldToDataType widths, never the generic SQLGlot integer hint. */
internal fun clickhouseLiteralType(node: Expression): DataType? {
    val literal = (if (node is Neg) node.thisArg else node) as? Literal ?: return null
    if (literal.isString) return if (node is Neg) null else clickhousePlainType(DType.VARCHAR)
    val spelling = literal.name
    val unsigned = spelling.toULongOrNull()
    if (unsigned != null) {
        val kind = if (node is Neg) when {
            unsigned == 0uL -> return null // Unary -0 normalization is outside this proof.
            unsigned <= 128uL -> DType.TINYINT
            unsigned <= 32768uL -> DType.SMALLINT
            unsigned <= 2147483648uL -> DType.INT
            unsigned <= 9223372036854775808uL -> DType.BIGINT
            else -> return null
        } else when {
            unsigned <= 255uL -> DType.UTINYINT
            unsigned <= 65535uL -> DType.USMALLINT
            unsigned <= 4294967295uL -> DType.UINT
            else -> DType.UBIGINT
        }
        return clickhousePlainType(kind)
    }
    if (spelling.any { it == '.' || it == 'e' || it == 'E' } && spelling.toDoubleOrNull()?.isFinite() == true)
        return clickhousePlainType(DType.DOUBLE)
    return null
}

internal fun clickhousePlainType(kind: DType) = DataType(args("this" to kind, "nullable" to false, "nested" to false))

/** Bounded getLeastSupertype joins. No Variant fallback, lossy numeric coercion or decimal join guesses. */
internal fun clickhouseCommonType(types: List<DataType>, depth: Int = 0): DataType? {
    if (types.isEmpty() || depth > 128 || types.any { it.args["nullable"] !is kotlin.Boolean }) return null
    val nullable = types.any { it.args["nullable"] == true }
    val plain = types.map { (it.copy() as DataType).also { copy -> copy.set("nullable", false) } }
    val joined = if (plain.all { it == plain.first() }) plain.first()
    else {
        val signed = mapOf(DType.TINYINT to 8, DType.SMALLINT to 16, DType.INT to 32, DType.BIGINT to 64)
        val unsigned = mapOf(DType.UTINYINT to 8, DType.USMALLINT to 16, DType.UINT to 32, DType.UBIGINT to 64)
        val kinds = plain.map { it.thisArg }
        when {
            kinds.all { it in signed || it in unsigned } -> {
                val signedBits = kinds.maxOf { signed[it] ?: 0 }
                val unsignedBits = kinds.maxOf { unsigned[it] ?: 0 }
                if (signedBits == 0) clickhousePlainType(unsigned.entries.first { it.value == unsignedBits }.key)
                else {
                    val bits = listOf(8, 16, 32, 64).firstOrNull { it >= signedBits && it > unsignedBits } ?: return null
                    clickhousePlainType(signed.entries.first { it.value == bits }.key)
                }
            }
            kinds.all { it == DType.FLOAT || it == DType.DOUBLE } -> clickhousePlainType(DType.DOUBLE)
            kinds.all { it == DType.ARRAY } -> {
                val elements = plain.map { it.expressionsArg.singleOrNull() as? DataType ?: return null }
                val element = clickhouseCommonType(elements, depth + 1) ?: return null
                DataType(args("this" to DType.ARRAY, "nullable" to false, "nested" to true, "expressions" to listOf(element)))
            }
            kinds.all { it == DType.STRUCT } -> {
                val count = plain.first().expressionsArg.size
                if (plain.any { it.expressionsArg.size != count || it.expressionsArg.any { field -> field !is DataType } }) return null
                val fields = (0 until count).map { index -> clickhouseCommonType(plain.map { it.expressionsArg[index] as DataType }, depth + 1) ?: return null }
                DataType(args("this" to DType.STRUCT, "nullable" to false, "nested" to true, "expressions" to fields))
            }
            else -> return null
        }
    }
    if (nullable && joined.thisArg in setOf(DType.ARRAY, DType.MAP, DType.STRUCT)) return null
    joined.set("nullable", nullable)
    return joined
}

/** FunctionArrayMapped recursively strips LowCardinality before declaring lambda inputs. */
internal fun clickhouseLambdaInputType(type: DataType, checkCanceled: () -> Unit, depth: Int = 0): DataType? {
    checkCanceled()
    if (depth > 128) return null
    if (type.thisArg == DType.LOWCARDINALITY) {
        val child = type.expressionsArg.singleOrNull() as? DataType ?: return null
        // Numeric dictionary types require a separate suspicious-type setting.
        // Do not assume that constructor is permitted merely to strip its wrapper.
        if (child.thisArg !in setOf(DType.TEXT, DType.VARCHAR, DType.FIXEDSTRING)) return null
        return clickhouseLambdaInputType(child, checkCanceled, depth + 1)
    }
    val copy = type.copy() as DataType
    val fields = copy.expressionsArg.map { field ->
        when (field) {
            is DataType -> clickhouseLambdaInputType(field, checkCanceled, depth + 1) ?: return null
            is ColumnDef -> (field.copy() as ColumnDef).also { definition ->
                val kind = field.args["kind"] as? DataType ?: return null
                definition.set("kind", clickhouseLambdaInputType(kind, checkCanceled, depth + 1) ?: return null)
            }
            else -> field
        }
    }
    if (fields.isNotEmpty()) copy.set("expressions", fields)
    return copy
}

/** DecimalUtils::binaryOpResult for two Decimal operands. Mixed overloads stay unknown. */
internal fun clickhouseDecimalProduct(types: List<DataType>): DataType? {
    fun decimal(type: DataType): Pair<Int, Int>? {
        val parameters = type.expressionsArg.map {
            val value = if (it is DataTypeParam) it.thisArg else it
            (value as? Literal)?.name?.toIntOrNull() ?: return null
        }
        return when (type.thisArg) {
            DType.DECIMAL -> {
                val precision = parameters.getOrNull(0) ?: return null
                val scale = parameters.getOrNull(1) ?: return null
                val storage = listOf(9, 18, 38, 76).firstOrNull { it >= precision } ?: return null
                if (precision !in 1..76 || scale !in 0..precision) return null
                storage to scale
            }
            DType.DECIMAL32, DType.DECIMAL64, DType.DECIMAL128, DType.DECIMAL256 -> {
                val storage = mapOf(DType.DECIMAL32 to 9, DType.DECIMAL64 to 18, DType.DECIMAL128 to 38, DType.DECIMAL256 to 76).getValue(type.thisArg as DType)
                val scale = parameters.singleOrNull() ?: return null
                if (scale !in 0..storage) return null
                storage to scale
            }
            else -> null
        }
    }
    val decimals = types.mapNotNull(::decimal)
    if (types.size != 2 || decimals.size != 2) return null
    val precision = decimals.maxOf { it.first }
    val scale = decimals.sumOf { it.second }
    if (scale > precision) return null
    return DataType(args("this" to DType.DECIMAL, "nullable" to false, "nested" to false,
        "expressions" to listOf(precision, scale).map { DataTypeParam(args("this" to Literal.number(it.toString()))) }))
}
