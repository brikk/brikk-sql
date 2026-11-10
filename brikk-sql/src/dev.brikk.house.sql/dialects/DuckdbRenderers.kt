package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.DuckdbDeallocate
import dev.brikk.house.sql.ast.DuckdbExecute
import dev.brikk.house.sql.ast.DuckdbPrepare
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.generator.Generator

internal fun Generator.duckdbSessionStatementSql(expression: Expression): String {
    if (!dialect.executesSessionStatements) unexecutable("$targetName cannot execute DuckDB prepared/session statements")
    val name = sql(expression, "this")
    return when (expression) {
        is DuckdbPrepare -> "PREPARE $name AS ${sql(expression, "expression")}"
        is DuckdbExecute -> expressions(expression, flat = true).let { "EXECUTE $name" + if (it.isEmpty()) "" else "($it)" }
        is DuckdbDeallocate -> "DEALLOCATE $name"
        else -> "RESET VARIABLE $name"
    }
}

internal fun Generator.duckdbBinaryLiteralSql(e: Expression): String = run {
    if (!dialect.executesBinaryLiterals) unexecutable("DuckDB BLOB/BIT literals have no verified cross-target value/type translation")
    val kind = e.text("kind")
    val value = e.text("this")
    require(kind == "X" && value.length % 2 == 0 && value.all { it in "0123456789abcdefABCDEF" } ||
        kind == "B" && value.all { it == '0' || it == '1' }) { "Invalid DuckDB binary literal AST" }
    "$kind'$value'"
}

internal fun Generator.duckdbRecursiveViewPropertySql(): String = run {
    if (!dialect.executesRecursiveViews) unexecutable("${dialect.name} has no verified DuckDB recursive view translation")
    "RECURSIVE"
}
