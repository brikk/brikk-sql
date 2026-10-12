package dev.brikk.house.sql.verify

import dev.brikk.house.sql.parser.ColumnUnit
import dev.brikk.house.sql.parser.sourceOffset

/** The outcome of one [SqlVerifier] call. Only [Accepted] and [Rejected] are verdicts. */
public sealed interface VerifyResult {
    /** The engine's parser accepted the SQL. */
    public data object Accepted : VerifyResult

    /** The engine's parser rejected the SQL; [errors] are in the engine's order. */
    public data class Rejected(public val errors: List<VerifyError>) : VerifyResult {
        public constructor(error: VerifyError) : this(listOf(error))

        init {
            require(errors.isNotEmpty()) { "A rejection needs at least one error" }
        }

        /** The engine's first error. */
        public val error: VerifyError get() = errors.first()
    }

    /**
     * The verifier works but could not check this SQL, for example a statement kind its
     * parse-only channel cannot serialize. Neither acceptance nor rejection.
     */
    public data class Unverified(public val reason: String) : VerifyResult

    /** The verifier cannot run at all (engine, parser or driver missing, or closed). */
    public data class Unavailable(public val reason: String) : VerifyResult
}

/**
 * One engine error, positioned in the verified text when the engine reported where.
 *
 * [offset] and [endOffset] are 0-based UTF-16 offsets (end exclusive), ready to use as an
 * editor range. [line] and [column] are 1-based; [column] counts UTF-16 code units and lines
 * break at LF, so a CR before it belongs to the line. Engines that report code points or
 * 0-based columns are converted. A position the engine did not report, or one outside the text,
 * is null.
 */
public data class VerifyError(
    public val message: String,
    public val line: Int? = null,
    public val column: Int? = null,
    public val offset: Int? = null,
    public val endOffset: Int? = null,
) {
    public companion object {
        /**
         * An error at an engine's [line] and [column] in [text], counted in [unit] from
         * [firstColumn] (see core's [sourceOffset]).
         */
        public fun at(
            text: String,
            message: String,
            line: Int,
            column: Int,
            unit: ColumnUnit = ColumnUnit.UTF16,
            firstColumn: Int = 1,
        ): VerifyError = sourceOffset(text, line, column, unit, firstColumn)
            ?.let { atOffset(text, message, it) }
            ?: VerifyError(message)

        /** An error at the UTF-16 [offset] in [text], optionally spanning to [endOffset]. */
        public fun atOffset(text: String, message: String, offset: Int, endOffset: Int? = null): VerifyError {
            if (offset !in 0..text.length) return VerifyError(message)
            val lineStart = text.lastIndexOf('\n', offset - 1) + 1
            return VerifyError(
                message = message,
                line = text.take(offset).count { it == '\n' } + 1,
                column = offset - lineStart + 1,
                offset = offset,
                endOffset = endOffset?.takeIf { it in offset..text.length },
            )
        }
    }
}

/**
 * Maps positions reported for `prefix + fragment` back onto the fragment, for verifiers that
 * wrap an expression (for example in `SELECT `) before the engine parses it. Positions inside
 * the prefix move to the fragment's start.
 */
public fun VerifyResult.withoutPrefix(prefix: String): VerifyResult {
    require('\n' !in prefix) { "A wrapper prefix must not span lines" }
    if (this !is VerifyResult.Rejected) return this
    val shift = prefix.length
    return VerifyResult.Rejected(errors.map { error ->
        error.copy(
            column = error.column?.let { if (error.line == 1) (it - shift).coerceAtLeast(1) else it },
            offset = error.offset?.let { (it - shift).coerceAtLeast(0) },
            endOffset = error.endOffset?.let { (it - shift).coerceAtLeast(0) },
        )
    })
}
