package dev.brikk.house.sql.intellij.pipe

import com.intellij.psi.PsiFile
import dev.brikk.house.sql.verify.SqlVerifier
import dev.brikk.house.sql.verify.VerifyError
import dev.brikk.house.sql.verify.VerifyResult

/** A dialect's own check of SQL. It must have no side effects and let cancellation propagate. */
public interface NativeSqlValidator {
    public val timing: ValidationTiming

    /** [file] is the SQL's source file, when known. */
    public fun validate(sql: String, file: PsiFile?): NativeVerdict

    /**
     * The check behind [validate], with the engine's error positions for editor diagnostics. By
     * default [validate] without positions; validators over a `SqlVerifier` return its result.
     */
    public fun verify(sql: String, file: PsiFile?): VerifyResult = when (val verdict = validate(sql, file)) {
        NativeVerdict.Accepted -> VerifyResult.Accepted
        is NativeVerdict.Rejected -> VerifyResult.Rejected(VerifyError(verdict.message))
        NativeVerdict.Unavailable -> VerifyResult.Unavailable("The native validator did not check this SQL.")
    }

    /** A re-implemented grammar rather than the engine's own, so its rejections are shown as warnings. */
    public val advisory: Boolean get() = false

    public companion object {
        /** A validator over [verifier], at [timing] for PIPE execution. */
        public fun of(verifier: SqlVerifier, timing: ValidationTiming = ValidationTiming.Lowering): NativeSqlValidator =
            object : NativeSqlValidator {
                override val timing: ValidationTiming = timing
                override val advisory: Boolean get() = verifier.advisory

                override fun validate(sql: String, file: PsiFile?): NativeVerdict = verify(sql, file).toNativeVerdict()

                override fun verify(sql: String, file: PsiFile?): VerifyResult = verifier.verify(sql)
            }
    }
}

/** The execution verdict of a verification: only a rejection blocks; no verdict lets the core lowering decide. */
public fun VerifyResult.toNativeVerdict(): NativeVerdict = when (this) {
    VerifyResult.Accepted -> NativeVerdict.Accepted
    is VerifyResult.Rejected -> NativeVerdict.Rejected(errors.joinToString("\n") { it.message })
    is VerifyResult.Unverified, is VerifyResult.Unavailable -> NativeVerdict.Unavailable
}

public enum class ValidationTiming {
    /** Every lowering, preview included; named parameters stay masked until substituted. */
    Lowering,

    /** Only the final SQL, after parameter substitution and before submission. */
    Submission,
}

public sealed interface NativeVerdict {
    public data object Accepted : NativeVerdict

    /** Blocks execution. */
    public data class Rejected(public val message: String) : NativeVerdict

    /** Not checked; execution proceeds on the core lowering alone. */
    public data object Unavailable : NativeVerdict
}
