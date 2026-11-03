package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Attach
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.HistoricalData
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Kwarg
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.parser.TokenType

/** Pure source triage. Storage layout/properties may be lost by the upstream-shaped DDL
 * model, so never pass them through executable transformation on the strength of that AST.
 * This does not reserve extension keywords in expressions or infer a backend from a name.
 */
internal fun duckdbStorageDdlIssue(sql:String):String? {
    val tokens=Dialects.DUCKDB.tokenize(sql)
    val top=mutableListOf<String>()
    var depth=0
    for(token in tokens) {
        if(depth==0 && token.tokenType !in setOf(TokenType.STRING,TokenType.IDENTIFIER))top+=token.text.uppercase()
        if(token.tokenType==TokenType.L_PAREN)depth++
        if(token.tokenType==TokenType.R_PAREN)depth--
    }
    if(top.firstOrNull() !in setOf("CREATE","ALTER") || "TABLE" !in top)return null
    val beforeQuery=top.takeWhile { it!="AS" }
    val layout=beforeQuery.any { it in setOf("PARTITION BY","PARTITIONED BY","PARTITIONED_BY") } ||
        beforeQuery.windowed(2).any { it==listOf("SORTED","BY") }
    val properties=beforeQuery.windowed(2).any { it==listOf("WITH","(") || top.first()=="ALTER" && it==listOf("SET","(") }
    return if(layout || properties)"DuckDB storage layout/table-property DDL has no lossless executable AST model; retain authored SQL and use explicit native execution" else null
}
internal fun Expression.hasDuckdbSnapshot():Boolean = walk().any { node ->
    node is HistoricalData || node is Table && node.args["version"]!=null ||
        node is Anonymous && node.name.lowercase() in setOf("delta_scan","iceberg_scan") && node.expressionsArg.filterIsInstance<Expression>().any {
            (it is EQ || it is Kwarg || it is PropertyEQ) && (it.thisArg as? Expression)?.name?.lowercase() in setOf("version","timestamp","snapshot_from_id","snapshot_from_timestamp")
        }
}
internal fun Expression.isDuckdbStorageAttach():Boolean {
    if(this !is Attach)return false
    val target=thisArg as? Expression
    if(target?.name?.startsWith("ducklake:",true)==true || (target?.thisArg as? Expression)?.name?.startsWith("ducklake:",true)==true)return true
    return expressionsArg.filterIsInstance<Expression>().any {
        (it.thisArg as? Expression)?.name.equals("TYPE",true) && (it.expressionArg as? Expression)?.name?.lowercase() in setOf("ducklake","iceberg","delta")
    }
}
