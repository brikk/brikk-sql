package dev.brikk.house.sql.ast

internal fun Expression.isTriggerDdl(): kotlin.Boolean = (this is Create || this is Drop) && text("kind").equals("TRIGGER", ignoreCase = true)

/** Trigger transition relations are local to the body and never qualify a physical write target. */
internal fun Table.isTriggerTransitionReference(): kotlin.Boolean {
    if (db.isNotEmpty() || catalog.isNotEmpty()) return false
    val properties = findAncestor<TriggerProperties>() ?: return false
    val body = properties.args["execute"] as? Expression ?: return false
    if (generateSequence(parent) { it.parent }.none { it === body }) return false
    val aliases = properties.args["referencing"] as? TriggerReferencing ?: return false
    return listOf("old", "new").any { (aliases.args[it] as? Identifier)?.name?.equals(name, ignoreCase = true) == true }
}

internal fun Table.isTriggerName(): kotlin.Boolean = parent?.let { it is Drop && it.isTriggerDdl() } == true
