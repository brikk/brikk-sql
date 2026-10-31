package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Kwarg
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.ReadParquet
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.ToMap
import dev.brikk.house.sql.ast.Table

internal fun Expression.isDuckdbParquetCall():Boolean {
    if((parent as? Dot)?.expressionArg === this)return false
    val table=parent as? Table
    if(table!=null && (table.db.isNotEmpty() || table.catalog.isNotEmpty()))return false
    return this is ReadParquet || this is Anonymous && name.lowercase() in setOf("read_parquet","parquet_scan")
}

internal fun Expression.duckdbParquetOptions():Map<String,Expression>? {
    if(!isDuckdbParquetCall())return null
    val options=linkedMapOf<String,Expression>()
    for(arg in expressionsArg.filterIsInstance<Expression>()) {
        if(arg !is EQ && arg !is Kwarg && arg !is PropertyEQ)continue
        val name=(arg.thisArg as? Expression)?.name?.lowercase() ?: return null
        if(name in options)return null
        options[name]=arg.expressionArg as? Expression ?: return null
    }
    return options
}

/** Names/types are declarations only. Defaults and paths are never evaluated or inspected. */
internal fun Expression.duckdbParquetDeclaredColumns():List<Pair<String,String>>? {
    val options=duckdbParquetOptions() ?: return null
    if("schema" in options && "bound_schema" in options)return null
    if((options["union_by_name"] as? BooleanNode)?.thisArg==true)return null
    if("bound_schema" in options) {
        // Only the explicit non-Hive declaration is modeled. This is not a default map:
        // native projection of a missing physical field can still fail at execution.
        if((options["hive_partitioning"] as? BooleanNode)?.thisArg!=false || (options["allow_empty"] as? BooleanNode)?.thisArg==true)return null
        val struct=options["bound_schema"] as? Struct ?: return null
        val columns=struct.expressionsArg.map { field ->
            if(field !is PropertyEQ)return null
            val name=(field.thisArg as? Expression)?.name ?: return null
            val type=(field.expressionArg as? Literal)?.takeIf { it.isString }?.name ?: return null
            if(name.isEmpty() || type.isBlank())return null
            name to type
        }
        if(columns.isEmpty() || columns.map { it.first.lowercase() }.distinct().size!=columns.size)return null
        for((option,type)in listOf("filename" to "VARCHAR","file_row_number" to "BIGINT")) {
            val flag=options[option] ?: continue
            if(flag !is BooleanNode)return null
            if(flag.thisArg==true && columns.none { it.first.equals(option,true) && it.second.equals(type,true) })return null
        }
        return columns
    }
    val declared=options["schema"] ?: return null
    if((options["hive_partitioning"] as? BooleanNode)?.thisArg==true)return null
    val pairs=when(declared) {
        is MapNode -> {
            val keys=(declared.args["keys"] as? ArrayNode)?.expressionsArg?.filterIsInstance<Expression>() ?: return null
            val values=(declared.args["values"] as? ArrayNode)?.expressionsArg?.filterIsInstance<Expression>() ?: return null
            if(keys.size!=values.size)return null
            keys.zip(values)
        }
        is ToMap -> ((declared.thisArg as? Struct)?.expressionsArg ?: return null).map { field ->
            if(field !is PropertyEQ)return null
            (field.thisArg as? Expression ?: return null) to (field.expressionArg as? Expression ?: return null)
        }
        else -> return null
    }
    val keys=pairs.map { it.first }
    val values=pairs.map { it.second }
    if(keys.isEmpty() || keys.size!=values.size)return null
    if(keys.any { it !is Literal } || keys.map { (it as Literal).isString }.distinct().size!=1)return null
    if(keys.map { it.name }.distinct().size!=keys.size)return null
    val columns=values.map { value ->
        val struct=value as? Struct ?: return null
        val fields=linkedMapOf<String,Expression>()
        for(field in struct.expressionsArg.filterIsInstance<Expression>()) {
            if(field !is PropertyEQ && field !is Kwarg)return null
            val name=(field.thisArg as? Expression)?.name?.lowercase() ?: return null
            if(name in fields)return null
            fields[name]=field.expressionArg as? Expression ?: return null
        }
        if(fields.keys!=setOf("name","type","default_value"))return null
        val name=(fields["name"] as? Literal)?.takeIf { it.isString }?.name ?: return null
        val type=(fields["type"] as? Literal)?.takeIf { it.isString }?.name ?: return null
        if(name.isEmpty() || type.isBlank())return null
        name to type
    }.toMutableList()
    for((option,type)in listOf("filename" to "VARCHAR","file_row_number" to "BIGINT")) {
        when(val value=options[option]) {
            null -> {}
            is BooleanNode -> if(value.thisArg==true)columns+=option to type
            is Literal -> if(option=="filename" && value.isString)columns+=value.name to type else return null
            else -> return null
        }
    }
    return columns.takeIf { it.map { column -> column.first.lowercase() }.distinct().size==it.size }
}

internal fun Expression.hasDuckdbParquetCall():Boolean = walk().any { it.isDuckdbParquetCall() }
internal fun Expression.hasDuckdbParquetContract():Boolean = hasDuckdbParquetCall() &&
    (walk().any { it.isDuckdbParquetCall() && it.duckdbParquetOptions()?.keys?.any { name -> name in setOf("schema","bound_schema") }==true } ||
        walk().any { it is Column && it.name.equals("file_row_group_number",true) })
