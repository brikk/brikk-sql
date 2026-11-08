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
import kotlin.test.assertNull
import kotlin.test.assertSame

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
}
