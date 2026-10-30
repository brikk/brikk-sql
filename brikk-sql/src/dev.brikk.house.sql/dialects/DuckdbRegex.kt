package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Kwarg
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.ast.RegexpExtract
import dev.brikk.house.sql.ast.RegexpExtractAll
import dev.brikk.house.sql.ast.RegexpReplace
import dev.brikk.house.sql.ast.args

internal fun Expression.duckdbRegexName(): String? {
    if((parent as? Dot)?.expressionArg === this)return null
    return when(this) {
        is RegexpExtract -> "REGEXP_EXTRACT"
        is RegexpExtractAll -> "REGEXP_EXTRACT_ALL"
        is RegexpReplace -> "REGEXP_REPLACE"
        is Anonymous -> name.uppercase().takeIf { it in setOf("REGEXP_EXTRACT","REGEXP_EXTRACT_ALL","REGEXP_REPLACE") }
        else -> null
    }
}
internal fun Expression.duckdbRegexArguments(): List<Expression>? {
    val name=duckdbRegexName() ?: return null
    if(this is Anonymous)return expressionsArg.filterIsInstance<Expression>()
    if(args["position"]!=null || args["occurrence"]!=null)return null
    val keys=if(name=="REGEXP_REPLACE")listOf("this","expression","replacement","modifiers")else listOf("this","expression","group","parameters")
    return keys.mapNotNull { args[it] as? Expression }
}
internal fun Expression.duckdbRegexReturnType(): DataType? {
    val name=duckdbRegexName() ?: return null
    val values=duckdbRegexArguments() ?: return null
    if(values.any { it is Kwarg || it is PropertyEQ })return null
    if(values.size !in (if(name=="REGEXP_REPLACE")3..4 else 2..4))return null
    if(this is Anonymous)return null // Unmodeled arity/named roles must not invent a schema.
    var result=DataType(args("this" to DType.VARCHAR))
    if(name!="REGEXP_REPLACE") {
        val group=values.getOrNull(2)
        if(group is ArrayNode) {
            val names=group.expressionsArg.map { (it as? Literal)?.takeIf { it.isString }?.name ?: return null }
            if(names.isEmpty() || names.map { it.lowercase() }.distinct().size!=names.size)return null
            result=DataType(args("this" to DType.STRUCT,"nested" to true,"expressions" to names.map {
                ColumnDef(args("this" to Identifier(args("this" to it,"quoted" to true)),"kind" to DataType(args("this" to DType.VARCHAR))))
            }))
        }else if(group!=null && group !is Null && (group !is Literal || name=="REGEXP_EXTRACT_ALL" && group.isString))return null
        if(name=="REGEXP_EXTRACT_ALL")result=DataType(args("this" to DType.ARRAY,"expressions" to listOf(result),"nested" to true))
    }
    return result
}
internal fun Expression.hasDuckdbRegexOverload(): Boolean {
    val name=duckdbRegexName() ?: return false
    val values=duckdbRegexArguments() ?: return false
    if(values.any { it is Kwarg || it is PropertyEQ })return true
    return name in setOf("REGEXP_EXTRACT","REGEXP_EXTRACT_ALL") &&
        values.getOrNull(2)?.let { it is ArrayNode || it is Literal && it.isString } == true
}

internal fun buildDuckdbRegexpExtract(all:Boolean):(List<Expression?>)->Expression = { values ->
    if(values.size in 2..4)buildRegexpExtract(all)(values)
    else Anonymous(args("this" to if(all)"regexp_extract_all" else "regexp_extract","expressions" to values))
}
