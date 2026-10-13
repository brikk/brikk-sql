package dev.brikk.house.sql.intellij.pipe

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.pipeRunnableStageKeywords
import org.jetbrains.annotations.ApiStatus

/**
 * The stage phrases core parses for [dialect] that core lowers for it and the engine runs as
 * lowered, in core's order (`pipeRunnableStageKeywords`, from the evidence core's dialect records).
 * A dialect without recorded evidence gets every phrase core parses; one core does not know gets none.
 */
@ApiStatus.Experimental
public fun pipeStagePhrases(dialect: String): List<String> =
    if (Dialects.forNameOrNull(dialect) == null) emptyList() else pipeRunnableStageKeywords(dialect)
