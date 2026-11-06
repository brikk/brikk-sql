package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.ast.Expression

/** Lossless native command boundary, not a row-producing query or an instruction to run it. */
internal fun Expression.isDuckdbCheckpointCommand(): Boolean = this is Command &&
    (name.equals("CHECKPOINT", true) || name.equals("FORCE", true) && text("expression").trimStart().startsWith("CHECKPOINT", true))
