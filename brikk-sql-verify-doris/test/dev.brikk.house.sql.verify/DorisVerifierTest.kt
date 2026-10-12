package dev.brikk.house.sql.verify

import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class DorisVerifierTest {
    private val verifier = DorisVerifier.create(Path.of(checkNotNull(System.getProperty("brikk.doris.parser.jar"))))

    @Test
    fun acceptsStatementsScriptsAndExpressions() {
        assertIs<VerifyResult.Accepted>(verifier.verify("SELECT a FROM t WHERE b = 1"))
        assertIs<VerifyResult.Accepted>(verifier.verifyExpression("DATE_TRUNC(c2, 'MONTH')"))
        assertIs<VerifyResult.Accepted>(verifier.verifyScript("SELECT 1;\r\nSELECT '\uD83D\uDE00' FROM t;\nSELECT 2"))
        assertEquals("doris", verifier.engine)
    }

    @Test
    fun rejectionsPointAtTheOffendingTokenAcrossSupplementaryCharactersAndCrlf() {
        for (payload in listOf("ascii", "\uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE80")) {
            for (separator in listOf(" ", "\n", "\r\n")) {
                val sql = "SELECT$separator'$payload' FROM WHERE"
                val where = sql.indexOf("WHERE")
                val line = sql.take(where).count { it == '\n' } + 1
                val column = where - (sql.lastIndexOf('\n', where - 1) + 1) + 1

                val error = assertIs<VerifyResult.Rejected>(verifier.verify(sql), sql).error
                assertEquals(listOf(line, column, where), listOf(error.line, error.column, error.offset), sql)

                val scriptError = assertIs<VerifyResult.Rejected>(verifier.verifyScript(sql), sql).errors.first()
                assertEquals(
                    listOf(line, column, where, where + "WHERE".length),
                    listOf(scriptError.line, scriptError.column, scriptError.offset, scriptError.endOffset),
                    sql,
                )
            }
        }
    }

    @Test
    fun scriptsReportEveryErrorInSourceOrder() {
        val script = "SELECT 1 FROM;\r\nSELECT '\uD83D\uDE00', FROM t;\nSELECT 2 ```"
        val semicolon = script.indexOf(';')
        val from = script.indexOf("FROM t")
        val backtick = script.lastIndexOf('`')
        assertEquals(
            listOf(
                VerifyError("no viable alternative at input 'FROM;'", 1, semicolon + 1, semicolon, semicolon + 1),
                VerifyError("extraneous input 'FROM'", 2, from - script.indexOf('\n'), from, from + "FROM".length),
                VerifyError("extraneous input '`'", 3, backtick - script.lastIndexOf('\n'), backtick, backtick + 1),
            ),
            assertIs<VerifyResult.Rejected>(verifier.verifyScript(script)).errors.map {
                it.copy(message = it.message.substringBefore(" expecting"))
            },
        )
    }

    @Test
    fun postProcessingFailuresEndTheScan() {
        val script = "SELECT * FROM a-b"
        val error = assertIs<VerifyResult.Rejected>(verifier.verifyScript(script)).error
        assertContains(error.message, "Possibly unquoted identifier a-b")
        assertEquals(15, error.offset)
    }

    @Test
    fun expressionErrorsIndexTheFragment() {
        val error = assertIs<VerifyResult.Rejected>(verifier.verifyExpression("1 +")).error
        assertEquals(listOf(1, 4, 3), listOf(error.line, error.column, error.offset))
    }

    @Test
    fun cancellationDuringAScriptPropagates() {
        var rules = 0
        val cancelled = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> {
            verifier.verifyScript("SELECT a, b, c FROM t WHERE a = 1") { if (++rules == 5) throw cancelled }
        }
        assertEquals(cancelled, thrown)
        assertEquals(5, rules)
    }

    @Test
    fun cancellationAtAnyRulePropagatesTheCallersException() {
        for (sql in listOf("SELECT * FROM a-b", "SELECT `a` FROM `t`", "SELECT 1 FROM;\nSELECT 2", "SELECT 1 FROM a-")) {
            var rules = 0
            verifier.verifyScript(sql) { rules++ }
            for (index in 1..rules) {
                val cancelled = CancellationException("cancelled at rule $index")
                var seen = 0
                val thrown = assertFailsWith<Throwable>("$sql, rule $index") {
                    verifier.verifyScript(sql) { if (++seen == index) throw cancelled }
                }
                assertSame(cancelled, thrown, "$sql, rule $index")
            }
        }
    }

    @Test
    fun aMissingParserIsReportedWithItsSource() {
        val missing = Path.of("build/no-such-doris-parser.jar")
        assertContains(
            assertFailsWith<IllegalStateException> { DorisVerifier.create(missing) }.message.orEmpty(),
            missing.toString(),
        )
        // This module does not bundle fe-sql-parser, so the classpath has none.
        val fromClasspath = assertFailsWith<IllegalStateException> { DorisVerifier.create() }
        assertTrue(fromClasspath.message.orEmpty().contains("classpath"), fromClasspath.message)
    }
}
