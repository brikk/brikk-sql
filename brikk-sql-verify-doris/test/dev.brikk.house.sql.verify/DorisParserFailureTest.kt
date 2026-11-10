package dev.brikk.house.sql.verify

import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

internal class DorisParserFailureTest {
    @Test
    fun syntaxListenerLexerTokensAndPostProcessingStillProducePositionedRejections() {
        val verifier = DorisVerifier.create(Path.of(checkNotNull(System.getProperty("brikk.doris.parser.jar"))))
        val linked = verifier.javaClass.getDeclaredField("linked").apply { isAccessible = true }.get(verifier)
        val instance = linked.javaClass.getDeclaredField("instance").apply { isAccessible = true }.get(linked)
        // UNRECOGNIZED lexer tokens (including unterminated strings) reach the syntax listener.
        for ((sql, exception) in listOf(
            "SELECT FROM WHERE" to "SyntaxParseException",
            "SELECT 'unterminated" to "SyntaxParseException",
            "SELECT \u0001" to "SyntaxParseException",
            "SELECT * FROM a-b" to "ParseException",
        )) {
            val thrown = assertFailsWith<InvocationTargetException> {
                instance.javaClass.getMethod("parseStatement", String::class.java).invoke(instance, sql)
            }
            assertEquals(exception, thrown.targetException.javaClass.simpleName, sql)
            val error = assertIs<VerifyResult.Rejected>(verifier.verify(sql), sql).error
            kotlin.test.assertNotNull(error.offset, sql)
            kotlin.test.assertNotNull(error.line, sql)
            kotlin.test.assertNotNull(error.column, sql)
        }
        assertEquals(3, assertIs<VerifyResult.Rejected>(verifier.verifyExpression("1 +")).error.offset)
    }

    @Test
    fun unexpectedParserFailuresPropagateTheOriginalInstanceFromStatementsAndExpressions() {
        assertPropagates(IllegalStateException("parser bug"))
    }

    @Test
    fun parserCancellationPropagatesTheOriginalInstanceFromStatementsAndExpressions() {
        assertPropagates(CancellationException("edited"))
    }

    private fun assertPropagates(failure: Exception) {
        // Exercise the actual reflected invocation without adding a public constructor or seam.
        val linkedClass = DorisVerifier::class.java.declaredClasses.single { it.simpleName == "LinkedParser" }
        val linked = linkedClass.getDeclaredConstructor(Class::class.java).apply { isAccessible = true }
            .newInstance(FailingParser::class.java)
        val parser = linkedClass.getDeclaredField("instance").apply { isAccessible = true }.get(linked) as FailingParser
        parser.failure = failure
        val verifier = DorisVerifier::class.java.getDeclaredConstructor(linkedClass).apply { isAccessible = true }
            .newInstance(linked)
        assertSame(failure, assertFailsWith<Exception> { verifier.verify("SELECT 1") })
        assertSame(failure, assertFailsWith<Exception> { verifier.verifyExpression("1") })
    }

    internal class FailingParser {
        lateinit var failure: Exception
        fun parseStatement(sql: String): Unit = throw failure
        fun parseExpression(sql: String): Unit = throw failure
        fun newLexer(sql: String): String = sql
        fun newParser(lexer: String): FailingParser = this
        fun multiStatements() = Unit
    }
}
