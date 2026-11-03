package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.ast.Copy
import dev.brikk.house.sql.ast.CopyParameter
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Kwarg
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.args

private fun Expression.unqualifiedCall():Boolean = (parent as? Dot)?.expressionArg !== this &&
    (parent as? Table)?.let { it.db.isEmpty() && it.catalog.isEmpty() }!=false

internal fun Expression.duckdbGeoJsonScalarType():DataType? {
    if(this !is Anonymous || !unqualifiedCall() || expressionsArg.size!=1)return null
    val type=when(name.uppercase()) { "ST_GEOMFROMGEOJSON"->DType.GEOMETRY;"ST_ASGEOJSON"->DType.JSON;"ST_ASTEXT"->DType.VARCHAR;else->return null }
    return DataType(args("this" to type))
}
internal fun Expression.duckdbGeoJsonScalarArguments():List<Expression>? {
    if(duckdbGeoJsonScalarType()==null)return null
    val value=expressionsArg.single() as? Expression ?: return null
    return listOf(if(value is Kwarg || value is PropertyEQ)value.expressionArg as? Expression ?: return null else value)
}
internal fun Expression.isDuckdbJsonReader():Boolean = this is Anonymous && unqualifiedCall() &&
    name.lowercase() in setOf("read_json","read_json_auto","read_ndjson","read_ndjson_auto")

private fun Expression.jsonOptions():Map<String,Expression>? {
    if(!isDuckdbJsonReader())return null
    val result=linkedMapOf<String,Expression>()
    for(arg in expressionsArg.filterIsInstance<Expression>())if(arg is EQ || arg is Kwarg || arg is PropertyEQ) {
        val name=(arg.thisArg as? Expression)?.name?.lowercase() ?: return null
        if(name in result)return null
        result[name]=arg.expressionArg as? Expression ?: return null
    }
    return result
}
internal fun Expression.duckdbGeoJsonDeclaredColumns():List<Pair<String,String>>? {
    val options=jsonOptions() ?: return null
    if((options["hive_partitioning"] as? BooleanNode)?.thisArg!=false)return null
    if("schema" in options || "bound_schema" in options || (options["union_by_name"] as? BooleanNode)?.thisArg==true)return null
    if(listOf("filename","file_row_number").any { key -> options[key]?.let { it !is BooleanNode || it.thisArg==true }==true })return null
    val struct=options["columns"] as? Struct ?: return null
    val columns=struct.expressionsArg.map { field ->
        if(field !is PropertyEQ)return null
        val name=(field.thisArg as? Expression)?.name ?: return null
        val type=(field.expressionArg as? Literal)?.takeIf { it.isString }?.name ?: return null
        name to type
    }
    return columns.takeIf { it.isNotEmpty() && it.map { column -> column.first.lowercase() }.distinct().size==it.size && it.all { column -> column.first.isNotEmpty() && column.second.isNotBlank() } }
}
internal fun Expression.hasDuckdbGeoJsonContract():Boolean = walk().any { node ->
    when(node) {
        is Copy -> (node.args["params"] as? List<*>).orEmpty().filterIsInstance<CopyParameter>().any { option ->
            val key=(option.thisArg as? Expression)?.name?.uppercase()
            key in setOf("ID_COLUMN","GEOMETRY_COLUMN","BBOX") || key=="FORMAT" && (option.expressionArg as? Expression)?.name?.uppercase() in setOf("GEOJSON","GEOJSONL")
        } || (node.args["files"] as? List<*>).orEmpty().filterIsInstance<Literal>().any { it.name.lowercase().endsWith(".geojson") || it.name.lowercase().endsWith(".geojsonl") }
        is Anonymous -> node.unqualifiedCall() && (node.name.uppercase() in setOf("ST_ASGEOJSON","ST_GEOMFROMGEOJSON") || node.isDuckdbJsonReader() &&
            (node.jsonOptions()?.containsKey("geojson")==true || node.jsonOptions()?.containsKey("columns")==true ||
                node.expressionsArg.filterIsInstance<Literal>().any { it.isString && (it.name.lowercase().endsWith(".geojson") || it.name.lowercase().endsWith(".geojsonl")) }))
        is Table -> (node.thisArg as? Literal)?.let { it.isString && (it.name.lowercase().endsWith(".geojson") || it.name.lowercase().endsWith(".geojsonl")) }==true
        else -> false
    }
}
