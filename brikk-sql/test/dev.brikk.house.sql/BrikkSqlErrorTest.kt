package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.UnknownDialectException
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.SchemaError
import dev.brikk.house.sql.optimizer.SqlglotError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.ParseErrorInfo
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.TranspileCertificationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrikkSqlErrorTest {
    private fun messageOnlyErrors(): List<RuntimeException> = listOf(
        ParseError("message"),
        UnsupportedError("message"),
        OptimizeError("message"),
        SchemaError("message"),
        ShapeError("message"),
        TranspileCertificationError("message"),
        SqlglotError("message"),
    )

    @Test
    fun existingConstructorsKeepTheirJvmSignatures() {
        val string = String::class.java
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val int = Int::class.javaPrimitiveType!!
        val list = List::class.java
        val throwable = Throwable::class.java
        val signatures = mapOf(
            ParseError::class.java to setOf(listOf(string, list), listOf(string, list, int, marker)),
            TokenError::class.java to setOf(listOf(string, throwable), listOf(string, throwable, int, marker)),
            UnsupportedError::class.java to setOf(listOf(string)),
            OptimizeError::class.java to setOf(listOf(string)),
            SchemaError::class.java to setOf(listOf(string)),
            ShapeError::class.java to setOf(listOf(string)),
            TranspileCertificationError::class.java to setOf(listOf(string)),
            SqlglotError::class.java to setOf(listOf(string)),
            UnknownDialectException::class.java to setOf(listOf(string)),
        )
        for ((type, expected) in signatures) {
            assertEquals(expected, type.constructors.map { it.parameterTypes.toList() }.toSet(), type.name)
        }
        val parse = ParseError::class.java.getConstructor(string, list, int, marker)
            .newInstance("message", null, 2, null)
        assertEquals(emptyList(), parse.errors)
        assertEquals("message", parse.message)
        val token = TokenError::class.java.getConstructor(string, throwable, int, marker)
            .newInstance("message", null, 2, null)
        assertEquals("message", token.message)
        assertNull(token.cause)
    }

    @Test
    fun existingErrorsKeepTheirMessagesCausesAndDetails() {
        for (error in messageOnlyErrors() + TokenError("message")) {
            assertEquals("message", error.message, error::class.simpleName)
            assertNull(error.cause, error::class.simpleName)
        }
        val cause = IllegalStateException("cause")
        assertSame(cause, TokenError("message", cause).cause)
        assertNull(TokenError("message", null).cause)
        val details = listOf(ParseErrorInfo("description", 1, 2, "before", "bad", "after"))
        assertSame(details, ParseError("message", details).errors)
    }

    @Test
    fun messageOnlyErrorsAllowCauseInitialization() {
        val cause = IllegalStateException("cause")
        for (error in messageOnlyErrors()) {
            assertSame(error, error.initCause(cause))
            assertSame(cause, error.cause)
            assertEquals("message", error.message)
        }
        assertFailsWith<IllegalStateException> { TokenError("message").initCause(cause) }
        assertFailsWith<IllegalStateException> { TokenError("message", cause).initCause(cause) }
    }

    @Test
    fun sqlErrorsShareTheBaseTypeButArgumentErrorsDoNot() {
        for (error in messageOnlyErrors() + TokenError("message")) {
            assertTrue(error is BrikkSqlError, error::class.simpleName)
        }
        val unknown: RuntimeException = UnknownDialectException("not-a-dialect")
        assertTrue(unknown is IllegalArgumentException)
        assertFalse(BrikkSqlError::class.java.isInstance(unknown))
    }

    @Test
    fun baseCatchHandlesParseErrors() {
        val error = ParseError("message")
        var caught: BrikkSqlError? = null
        try {
            throw error
        } catch (e: BrikkSqlError) {
            caught = e
        }
        assertSame(error, caught)
    }

    @Test
    fun baseConstructorsPreserveNullMessagesAndCauseInitialization() {
        val cause = IllegalStateException("cause")
        val messageOnly = BrikkSqlError(null)
        assertNull(messageOnly.message)
        assertNull(messageOnly.cause)
        assertSame(messageOnly, messageOnly.initCause(cause))
        assertSame(cause, messageOnly.cause)
        val withCause = BrikkSqlError(null, cause)
        assertNull(withCause.message)
        assertSame(cause, withCause.cause)
        assertFailsWith<IllegalStateException> { withCause.initCause(cause) }
        val nullCause = BrikkSqlError(null, null)
        assertNull(nullCause.message)
        assertNull(nullCause.cause)
        assertFailsWith<IllegalStateException> { nullCause.initCause(cause) }
    }
}
