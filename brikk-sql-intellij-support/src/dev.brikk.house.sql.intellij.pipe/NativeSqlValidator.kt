package dev.brikk.house.sql.intellij.pipe

import com.intellij.psi.PsiFile
import org.jetbrains.annotations.ApiStatus

/** A dialect's own check of generated SQL. It must have no side effects and let cancellation propagate. */
@ApiStatus.Experimental
public interface NativeSqlValidator {
    public val timing: ValidationTiming

    /** [file] is the SQL's source file, when known. */
    public fun validate(sql: String, file: PsiFile?): NativeVerdict
}

@ApiStatus.Experimental
public enum class ValidationTiming {
    /** Every lowering, preview included; named parameters stay masked until substituted. */
    Lowering,

    /** Only the final SQL, after parameter substitution and before submission. */
    Submission,
}

@ApiStatus.Experimental
public sealed interface NativeVerdict {
    public data object Accepted : NativeVerdict

    /** Blocks execution. */
    public data class Rejected(public val message: String) : NativeVerdict

    /** Not checked; execution proceeds on the core lowering alone. */
    public data object Unavailable : NativeVerdict
}
