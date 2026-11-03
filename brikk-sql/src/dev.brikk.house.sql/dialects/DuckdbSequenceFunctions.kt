package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Kwarg
import dev.brikk.house.sql.ast.PropertyEQ

internal fun Expression.isDuckdbSetval(): Boolean = this is Anonymous && name.equals("setval",true) && (parent as? Dot)?.expressionArg !== this

/** Static argument roles only. Never resolve or invoke the sequence. */
internal fun Expression.duckdbSetvalArguments(): List<Expression>? {
    if (!isDuckdbSetval()) return null
    val values=expressionsArg.filterIsInstance<Expression>()
    if(values.size !in 2..3)return null
    val parameters=listOf("sequence_name","value","is_called").take(values.size)
    val bound=arrayOfNulls<Expression>(values.size)
    var named=false
    for((index,value)in values.withIndex()) {
        val position=if(value is Kwarg || value is PropertyEQ) {
            named=true;parameters.indexOf((value.thisArg as? Expression)?.name?.lowercase())
        }else { if(named)return null;index }
        if(position !in bound.indices || bound[position]!=null)return null
        bound[position]=if(value is Kwarg || value is PropertyEQ)value.expressionArg as? Expression else value
    }
    return bound.filterNotNull().takeIf { it.size==values.size }
}
