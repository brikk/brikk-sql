package dev.brikk.house.sql

/**
 * Base type for SQL parsing, generation, optimization, shape and certification errors.
 *
 * The message-only constructor allows a later [initCause], as existing error classes do;
 * the two-argument constructor fixes the cause, even when it is null.
 */
open class BrikkSqlError : RuntimeException {
    constructor(message: String?) : super(message)

    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
