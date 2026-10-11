package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType

/** Outcome of [lowerForExecution]. */
sealed interface PipeLowering {
    /**
     * The lowered statement. Non-empty [blockers] (the generator's unsupported diagnostics) mean
     * the translation is lossy: it may be shown, but must not be executed.
     */
    data class Ok(val result: TranspileResult) : PipeLowering {
        val sql: String get() = result.sql
        val blockers: List<String> get() = result.unsupportedMessages
    }

    /** The text must not be executed; [range] locates the cause in the input when known. */
    data class Refused(val range: SyntacticRange?, val message: String) : PipeLowering

    /** The text holds no pipe and runs as written. */
    data object NotPipe : PipeLowering
}

/**
 * brikk-native: lowers [text], which must hold exactly one statement, to executable [dialect]
 * SQL with every pipe desugared, refusing whatever cannot run as standard SQL. Text whose
 * statement boundaries are unknown is refused, never [PipeLowering.NotPipe]. [allowFirstStage]
 * also lowers a plain query, such as the bare first stage of a pipe program (`FROM t`).
 */
fun lowerForExecution(text: String, dialect: String, allowFirstStage: Boolean = false): PipeLowering {
    val statements = SqlScriptSnapshot.parse(text, dialect).statements
    statements.firstNotNullOfOrNull { statement ->
        statement.boundaryError?.let { PipeLowering.Refused(SyntacticRange(statement.start, statement.end), it) }
    }?.let { return it }
    if (statements.none { it.hasPipe } && !allowFirstStage) return PipeLowering.NotPipe
    if (statements.count { it.hasSql } != 1) return PipeLowering.Refused(null, "Select exactly one statement to execute")
    return try {
        val fragment = SqlFragment(text, dialect)
        if (!fragment.containsPipe && !(allowFirstStage && fragment.ast is Query)) {
            return PipeLowering.Refused(null, "The statement is not a pipe query")
        }
        val result = fragment.toExecutable(dialect, pretty = true)
        if (Dialects.forName(dialect).tokenize(result.sql).any { it.tokenType == TokenType.PIPE_GT }) {
            PipeLowering.Refused(null, "Lowering left pipe syntax in the output")
        } else {
            PipeLowering.Ok(result)
        }
    } catch (e: RuntimeException) {
        when (e) {
            is ParseError -> {
                val error = e.errors.firstOrNull()
                val range = error?.start?.let { start -> SyntacticRange(start, error.end ?: start) }
                PipeLowering.Refused(range, error?.description ?: e.message.orEmpty())
            }
            is UnsupportedError, is TokenError, is ShapeError -> PipeLowering.Refused(null, e.message.orEmpty())
            else -> throw e
        }
    }
}
