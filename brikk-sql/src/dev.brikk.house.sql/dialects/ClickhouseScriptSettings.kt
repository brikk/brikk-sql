package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import kotlin.Boolean
import kotlin.String
import kotlin.collections.Map

/** Conditional state after successfully executing preceding statements, never a connection snapshot. */
internal class ClickhouseScriptSettings {
    private val values = linkedMapOf<String, Boolean?>()
    private val tracked = setOf("sql_compatible_division_for_integers", "decimal_check_overflow")
    fun snapshot(): Map<String, Boolean?> = values.toMap()

    /** Returns true when a reset/unsupported mutation made state explicitly unknown. */
    fun advance(statement: Expression?): Boolean {
        if (statement == null || statement is Command) {
            values.clear()
            return true
        }
        if (statement !is dev.brikk.house.sql.ast.Set) {
            if (statement is Select) return false
            values.clear()
            return true // Unknown commands/DDL do not carry invented session facts forward.
        }
        val entries = statement.expressionsArg.filterIsInstance<SetItem>()
        if (entries.isEmpty() || entries.size != statement.expressionsArg.size ||
            statement.args["unset"] == true || statement.args["tag"] == true ||
            entries.any { it.args["kind"] != null || it.thisArg !is EQ }) {
            values.clear()
            return true
        }
        // Changing profiles/compatibility can reset several defaults indirectly.
        if (entries.any { (it.thisArg as EQ).left.name.lowercase() in setOf("profile", "compatibility") }) {
            values.clear()
            return true
        }
        var uncertain = false
        val defaults = mutableSetOf<String>()
        for (entry in entries) {
            val assignment = entry.thisArg as EQ
            val name = assignment.left.name.lowercase()
            if (name !in tracked) continue
            val value = assignment.right
            val boolean = clickhouseBooleanSettingValue(value)
            values[name] = boolean
            if (boolean == null) uncertain = true
            if (value !is Literal && value.name.equals("DEFAULT", ignoreCase = true)) defaults.add(name)
        }
        // Native applies changes first and resets second, regardless of authored order.
        for (name in defaults) values[name] = null
        return uncertain
    }
}
