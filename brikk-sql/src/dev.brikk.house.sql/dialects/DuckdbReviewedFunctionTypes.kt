package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*

internal fun Expression.reviewedDuckdbFunctionName():String? =
    if(this is Anonymous && (parent as? Dot)?.expressionArg!==this)
        name.uppercase().takeIf { it in setOf("BINOM","DECIMAL_AVERAGE","LTTB","INVOKE","ASSERT_TRUE","VERTEX_EXTRACT") }
    else null

/** Native 2.0 result facts for proved signatures only. No implicit-cast/lambda/default guessing. */
internal fun Expression.reviewedDuckdbFunctionType():DataType? {
    val name=reviewedDuckdbFunctionName() ?: return null
    val values=expressionsArg.filterIsInstance<Expression>()
    if(values.any { it is Kwarg || it is PropertyEQ })return null
    fun type(index:Int)=(values.getOrNull(index)?.type as? DataType)?.takeUnless { it.isType(DType.UNKNOWN) }
    return when(name) {
        "BINOM" -> if(values.size==2 && values.indices.all { index ->
            val value=values[index]
            type(index)?.isType(DType.INT)==true && (value !is Literal || value.isInt && value.name.toLongOrNull()?.let { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }==true)
        })DataType.build(DType.INT128) else null
        "DECIMAL_AVERAGE" -> {
            val input=type(0) ?: return null
            val parameters=input.expressionsArg.map { ((((it as? Expression)?.thisArg) as? Literal)?.takeIf { it.isInt })?.name?.toIntOrNull() }
            if(values.size==1 && input.isType(DType.DECIMAL) && parameters.size==2 &&
                parameters[0]?.let { it in 1..38 }==true && parameters[1]?.let { it in 0..parameters[0]!! }==true)input.copy() as DataType else null
        }
        "LTTB" -> {
            if(values.size!=3)return null
            val x=type(0) ?: return null;val y=type(1) ?: return null;val n=type(2) ?: return null
            val xOk=x.isType(DType.FLOAT,DType.DOUBLE,DType.TIMESTAMP,DType.TIMESTAMPNTZ,DType.TIMESTAMP_S,DType.TIMESTAMP_MS,DType.TIMESTAMP_NS,DType.TIMESTAMPTZ) ||
                x.thisArg==DType.USERDEFINED && x.text("kind").equals("TIMESTAMPTZ_NS",true)
            if(!xOk || !y.isType(DType.FLOAT,DType.DOUBLE) || !n.isType(DType.TINYINT,DType.SMALLINT,DType.INT,DType.BIGINT))return null
            // The parity annotator labels bare decimal-text atoms DOUBLE; that is not
            // enough evidence for selecting a native FLOAT/DOUBLE overload.
            if(values.take(2).any { it is Literal && it.isNumber })return null
            val fields=listOf("x" to x,"y" to y).map { (label,type) -> ColumnDef(args("this" to Identifier(args("this" to label,"quoted" to false)),"kind" to type.copy())) }
            val struct=DataType(args("this" to DType.STRUCT,"nested" to true,"expressions" to fields))
            DataType(args("this" to DType.ARRAY,"nested" to true,"expressions" to listOf(struct)))
        }
        "VERTEX_EXTRACT" -> if(values.size==2 && type(0)?.isType(DType.GEOMETRY)==true && type(1)?.thisArg in DataType.TEXT_TYPES)DataType.build(DType.DOUBLE) else null
        // Registry ANY and macro NULL types are not a fixed SQL result contract.
        "INVOKE","ASSERT_TRUE" -> null
        else -> null
    }
}
