package dev.brikk.house.sql

import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.NativeError
import dev.brikk.house.sql.diagnostics.NativeOutcome
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.diagnostics.combineStatementDiagnostics
import dev.brikk.house.sql.diagnostics.distinctByRange
import dev.brikk.house.sql.diagnostics.localParseDiagnostics
import dev.brikk.house.sql.diagnostics.verificationRange
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDiagnosticsTest {
    private fun ranges(script: String, dialect: String = "duckdb") =
        SqlScriptSnapshot.parse(script, dialect).statements.mapNotNull { verificationRange(script, it) }

    private fun text(script: String, diagnostic: SqlDiagnostic) = script.substring(diagnostic.start, diagnostic.end)

    @Test
    fun verificationRangesStopBeforeTheTerminatorAndSkipCommentOnlySpans() {
        val script = "-- lead\nSELECT 1 ;\n\n/* only */\nSELECT 2"
        assertEquals(listOf("-- lead\nSELECT 1", "\n\n/* only */\nSELECT 2"), ranges(script).map { script.substring(it.first, it.last + 1) })
        assertEquals(listOf<IntRange>(), ranges("-- nothing\n"))
    }

    @Test
    fun localParseErrorsArePositionedInTheScript() {
        val script = "SELECT 1;\nSELECT * FROM t WHERE a = = 1;"
        val (first, second) = ranges(script)
        assertEquals(listOf(), localParseDiagnostics(script, first, "duckdb"))
        val errors = localParseDiagnostics(script, second, "duckdb")
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.all { it.source == DiagnosticSource.LOCAL_PARSER && it.start >= second.first && it.end <= second.last + 1 })
    }

    @Test
    fun aLexicalErrorMarksWhereItsTokenBegins() {
        val script = "SELECT 1;\nSELECT 'open"
        val error = localParseDiagnostics(script, script.indexOf("SELECT 'open") until script.length, "duckdb").single()
        assertEquals(script.indexOf('\'', 10), error.start)
    }

    @Test
    fun anUnknownDialectHasNoLocalDiagnostics() = assertEquals(listOf(), localParseDiagnostics("SELECT", 0 until 6, "no-such-engine"))

    private val local = listOf(SqlDiagnostic(DiagnosticSeverity.ERROR, "local", 7, 8, DiagnosticSource.LOCAL_PARSER))

    @Test
    fun aNativeRejectionReplacesTheLocalErrors() {
        val script = "x;\nSELECT foo bar baz"
        val range = 3 until script.length
        val outcome = combineStatementDiagnostics(script, range, local, NativeOutcome.Rejected(listOf(NativeError("engine", offset = 11))))
        val error = outcome.diagnostics.single()
        assertEquals("bar", text(script, error))
        assertEquals(DiagnosticSource.NATIVE to "engine", error.source to error.message)
        assertEquals(false, outcome.nativelyAccepted)
    }

    @Test
    fun aNativeAcceptanceClearsTheLocalFalsePositives() {
        val outcome = combineStatementDiagnostics("SELECT foo bar", 0 until 14, local, NativeOutcome.Accepted)
        assertEquals(listOf(), outcome.diagnostics)
        assertTrue(outcome.nativelyAccepted)
    }

    @Test
    fun noNativeVerdictKeepsTheLocalErrorsAndIsNeverClean() {
        val outcome = combineStatementDiagnostics("SELECT foo bar", 0 until 14, local, NativeOutcome.NoVerdict("engine unavailable"))
        assertEquals(local, outcome.diagnostics)
        assertEquals(false, outcome.nativelyAccepted)
    }

    @Test
    fun nativePositionsAreMappedExactly() {
        val script = "SELECT 1;\nSELECT 'x𝄞y' FROM"
        val range = 10 until script.length
        fun at(offset: Int?, end: Int? = null) =
            combineStatementDiagnostics(script, range, listOf(), NativeOutcome.Rejected(listOf(NativeError("e", offset, end)))).diagnostics.single()
        assertEquals("FROM", text(script, at(script.indexOf("FROM") - 10)))
        assertEquals("'x", text(script, at(7, 9)))
        // Inside a surrogate pair: the whole code point.
        assertEquals("𝄞", text(script, at(10)))
        // Past the end: the last character; unknown: the first non-blank one.
        assertEquals("M", text(script, at(500)))
        assertEquals("SELECT", text(script, at(null)))
        // An end beyond the statement is ignored.
        assertEquals("FROM", text(script, at(script.indexOf("FROM") - 10, 99)))
    }

    @Test
    fun aWarningSeverityIsKeptForAdvisoryEngines() {
        val outcome = combineStatementDiagnostics("SELECT x", 0 until 8, listOf(), NativeOutcome.Rejected(listOf(NativeError("e", 7))), DiagnosticSeverity.WARNING)
        assertEquals(DiagnosticSeverity.WARNING, outcome.diagnostics.single().severity)
    }

    @Test
    fun oneDiagnosticPerRange() {
        val twice = combineStatementDiagnostics("SELECT x", 0 until 8, listOf(), NativeOutcome.Rejected(listOf(NativeError("a", 7), NativeError("b", 7))))
        assertEquals(listOf("a"), twice.diagnostics.map { it.message })
        assertEquals(1, (local + local.map { it.copy(message = "other") }).distinctByRange().size)
    }

    @Test
    fun crlfScriptsMapByUtf16Offsets() {
        val script = "SELECT 1;\r\nSELECT bad bad"
        val range = ranges(script)[1]
        val error = combineStatementDiagnostics(script, range, listOf(), NativeOutcome.Rejected(listOf(NativeError("e", script.lastIndexOf("bad") - range.first)))).diagnostics.single()
        assertEquals(script.lastIndexOf("bad"), error.start)
    }
}
