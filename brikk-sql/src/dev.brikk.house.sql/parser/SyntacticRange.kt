package dev.brikk.house.sql.parser

/** A proved syntactic interval in the authored SQL, in UTF-16 units; [end] is exclusive. */
data class SyntacticRange(val start: Int, val end: Int)

/** Identity survives AST copies; deliberately separate from parity/serialized node metadata. */
internal class SyntaxOrigin(val range: SyntacticRange, val fromFirst: Boolean = false)
