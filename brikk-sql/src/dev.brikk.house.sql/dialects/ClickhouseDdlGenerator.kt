package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.generator.Generator
import kotlin.String

/** Only called by the ClickHouse generator. There is no fallback cross-dialect DDL rewrite. */
internal fun Generator.clickhouseDdlSql(e: Expression): String {
    fun part(key: String, prefix: String): String = if (e.args[key] == null) "" else prefix + sql(e, key)
    fun flag(key: String, text: String): String = if (e.args[key] == true) text else ""
    fun settings(key: String): String = (e.args[key] as? Expression)?.expressionsArg
        ?.filterIsInstance<Expression>()?.joinToString(", ") { "${sql(it, "this")} = ${sql(it, "expression")}" }.orEmpty()
    fun validity(): String = part("valid", " VALID ${e.text("valid_kind")} ")
    fun grants(): String = if (e.args["grants"] != null) " GRANTS (${expressions(e, key = "grants", flat = true)})" else ""
    return when (e) {
        is ClickhouseExplain -> "EXPLAIN ${e.text("kind")}" +
            (if (e.args["settings"] != null) " ${settings("settings")}" else "") + " " + sql(e, "this")
        is ClickhouseHypotheticalIndex -> "CREATE HYPOTHETICAL INDEX" + flag("exists", " IF NOT EXISTS") +
            " ${sql(e, "this")} ON ${sql(e, "table")} ${sql(e, "expression")}" + part("type", " TYPE ") + part("granularity", " GRANULARITY ")
        is ClickhouseHandler -> {
            val kind = e.text("kind")
            "$kind HANDLER" + flag("exists", if (kind == "CREATE") " IF NOT EXISTS" else " IF EXISTS") +
                " ${sql(e, "this")}" + part("cluster", " ON CLUSTER ") + part("protocol", " PROTOCOL ") +
                part("url", " URL " + e.text("match").let { if (it.isBlank()) "" else "$it " }) +
                (if (e.args["methods"] != null) " METHODS (${expressions(e, key = "methods", flat = true)})" else "") +
                part("type", " TYPE ") + part("expression", " AS ")
        }
        is ClickhouseCreateToken -> "CREATE TOKEN" + validity() + grants() + part("format", " FORMAT ")
        is ClickhouseGrant -> expressions(e, flat = true) + " ON " + sql(e, "this")
        is ClickhouseUserAuthentication -> "${e.text("kind")} USER" +
            flag("exists", if (e.text("kind") == "CREATE") " IF NOT EXISTS" else " IF EXISTS") +
            " ${sql(e, "this")}" + part("cluster", " ON CLUSTER ") + flag("add", " ADD") +
            " IDENTIFIED WITH ${expressions(e, flat = true)}"
        is ClickhouseAuthenticationMethod -> sql(e, "this") + part("expression", " BY ") + validity() + grants()
        is ClickhouseCredential -> sql(e, "this")
        is ClickhouseAlterAction -> {
            val kind = e.text("kind")
            val exists = flag("exists", " IF EXISTS")
            when (kind) {
                "ENUM" -> "MODIFY COLUMN$exists ${sql(e, "this")} ADD ENUM VALUES (${expressions(e, flat = true)})"
                "PROJECTION" -> "MODIFY PROJECTION$exists " + sql(e, "expression").removePrefix("PROJECTION ")
                "ADD PROJECTION" -> "ADD PROJECTION" + flag("exists", " IF NOT EXISTS") + " " +
                    sql(e, "expression").removePrefix("PROJECTION ") + flag("first", " FIRST") + part("after", " AFTER ")
                "DROP PROJECTION", "CLEAR PROJECTION", "MATERIALIZE PROJECTION" ->
                    "$kind$exists ${sql(e, "this")}" + part("partition", " IN ")
                else -> "MODIFY CONSTRAINT$exists ${sql(e, "this")} $kind " + sql(e, "expression")
            }
        }
        is ClickhouseProjection -> "PROJECTION ${sql(e, "this")} " +
            (if (e.args["index"] == true) "INDEX ${sql(e, "expression")} TYPE ${sql(e, "type")}" else "(${sql(e, "expression")})") +
            (if (e.args["settings"] != null) " WITH SETTINGS (${settings("settings")})" else "")
        is ClickhouseSystem -> "SYSTEM ${e.text("kind")}" + flag("all", " ALL BACKGROUND") + part("cluster", " ON CLUSTER ") + part("this", " ")
        is ClickhouseRefresh -> sql(e, "this") + " INCREMENTAL"
        else -> error("Not a native ClickHouse DDL node: ${e.key}")
    }
}

/** Redacted diagnostic rendering. Regular sql() and Serde deliberately retain authored credentials. */
fun redactedClickhouseSql(expression: Expression): String {
    val copy = expression.copy()
    for (credential in copy.findAll<ClickhouseCredential>()) credential.set("this", Literal.string("[REDACTED]"))
    return copy.sql("clickhouse")
}
