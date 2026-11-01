package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*

private val plainDecimalProjection = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

/** Only scalar literal projections, not folded expressions or an opaque function's arguments. */
internal fun clickhouseProjectionLiteral(raw: Expression): Expression? {
    var node = raw
    while (node is Alias || node is Paren) node = node.thisArg as? Expression ?: return null
    return node.takeIf { it is Literal || it is Null || it is dev.brikk.house.sql.ast.Boolean ||
        it is Neg && it.thisArg is Literal }
}

/** Tagged ParserNumber/FieldToDataType, separate from generic and contextual literal policies. */
internal fun clickhouseProjectionLiteralType(node: Expression): DataType? {
    if (node is Null) return clickhousePlainType(DType.NOTHING).also { it.set("nullable", true) }
    if (node is dev.brikk.house.sql.ast.Boolean) return clickhousePlainType(DType.BOOLEAN)
    val literal = (if (node is Neg) node.thisArg else node) as? Literal ?: return null
    if (literal.isString) return if (node is Neg) null else clickhousePlainType(DType.VARCHAR)
    val spelling = literal.name
    // The native parser's cleaned numeric token is bounded, and admits plain decimal numerals here.
    if (spelling.length > 319 || !plainDecimalProjection.matches(spelling)) return null
    clickhouseLiteralType(node)?.let { return it }
    if (node is Neg && spelling.toULongOrNull() == 0uL) return clickhousePlainType(DType.UTINYINT)
    // Out-of-range integer text falls back to the finite Float64 parser, not Int128/UInt128.
    return if (spelling.toDoubleOrNull()?.isFinite() == true) clickhousePlainType(DType.DOUBLE) else null
}
