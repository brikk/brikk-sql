package dev.brikk.house.sql.intellij.pipe

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.PipeLowering
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.TranspileResult
import dev.brikk.house.sql.shape.lowerForExecution
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
public sealed interface PipeTranslation {
    /** Generated SQL, not approval: a non-null [blocker] forbids executing it. */
    public data class Lowered(public val result: TranspileResult, public val verdict: NativeVerdict?) : PipeTranslation {
        public val sql: String get() = result.sql
        public val blocker: String? get() = when {
            result.unsupportedMessages.isNotEmpty() ->
                "Unsupported or lossy PIPE translation:\n" + result.unsupportedMessages.joinToString("\n")
            verdict is NativeVerdict.Rejected -> "Generated SQL failed validation: ${verdict.message}"
            else -> null
        }
    }

    /** [range] locates the cause in the input, when known. */
    public data class Refused(public val range: SyntacticRange?, public val message: String) : PipeTranslation

    public data object NotPipe : PipeTranslation
}

/**
 * Lowers one statement for execution. [firstStage] also accepts a bare first stage that
 * [PipeDialect.firstStage] allows; [substituted] says parameters already carry their values.
 * Boundaries are refused if either the profile's [PipeDialect.scriptRanges] or core rejects them.
 */
@ApiStatus.Experimental
public fun PipeDialect.lower(
    text: String,
    firstStage: Boolean = false,
    substituted: Boolean = false,
    file: PsiFile? = null,
): PipeTranslation {
    ProgressManager.checkCanceled()
    val statements = scriptRanges(text)
    statements.firstOrNull { it.boundaryError != null }?.let {
        return PipeTranslation.Refused(SyntacticRange(it.start, it.end), it.boundaryError.orEmpty())
    }
    val hasPipe = statements.any { it.hasPipe }
    if (!hasPipe && !firstStage) return PipeTranslation.NotPipe
    if (statements.count { it.hasSql } != 1) return PipeTranslation.Refused(null, "Select exactly one statement to execute.")
    if (!hasPipe && this.firstStage == FirstStageRule.FromOnly && !startsWithFrom(text))
        return PipeTranslation.Refused(null, "Only a FROM stage can run as the first stage.")
    val lowering = lowerForExecution(text, dialect, firstStage)
    ProgressManager.checkCanceled()
    return when (lowering) {
        PipeLowering.NotPipe -> PipeTranslation.NotPipe
        is PipeLowering.Refused -> PipeTranslation.Refused(lowering.range, lowering.message)
        is PipeLowering.Ok -> {
            val check = validator?.takeIf { it.timing == ValidationTiming.Lowering && lowering.blockers.isEmpty() }
                ?: return PipeTranslation.Lowered(lowering.result, null)
            val sql = if (substituted) lowering.sql else try {
                maskParameters(lowering.sql)
            } catch (e: ParseError) {
                return PipeTranslation.Refused(null, "Generated SQL could not be checked: ${e.message}")
            } catch (e: TokenError) {
                return PipeTranslation.Refused(null, "Generated SQL could not be checked: ${e.message}")
            } catch (e: ShapeError) {
                return PipeTranslation.Refused(null, "Generated SQL could not be checked: ${e.message}")
            }
            PipeTranslation.Lowered(lowering.result, check.validate(sql, file)).also { ProgressManager.checkCanceled() }
        }
    }
}

private fun PipeDialect.startsWithFrom(text: String): Boolean = try {
    Dialects.forName(dialect).tokenize(text).firstOrNull()?.tokenType == TokenType.FROM
} catch (_: TokenError) {
    false
}
