package dev.brikk.house.sql

import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.NativeError
import dev.brikk.house.sql.diagnostics.NativeOutcome
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.diagnostics.combineStatementDiagnostics
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
    fun localParsingIsCancellable() {
        val stop = IllegalStateException("canceled")
        val sql = "SELECT " + (1..20_000).joinToString(", ")
        assertEquals(stop, kotlin.runCatching { localParseDiagnostics(sql, sql.indices, "duckdb") { throw stop } }.exceptionOrNull())
    }

    @Test
    fun aLexicalErrorMarksWhereItsTokenBegins() {
        val script = "SELECT 1;\nSELECT 'open"
        val error = localParseDiagnostics(script, script.indexOf("SELECT 'open") until script.length, "duckdb").single()
        assertEquals(script.indexOf('\'', 10), error.start)
    }

    @Test
    fun anUnknownDialectHasNoLocalDiagnostics() = assertEquals(listOf(), localParseDiagnostics("SELECT", 0 until 6, "no-such-engine"))

    private fun local(start: Int, end: Int = start + 1, source: DiagnosticSource = DiagnosticSource.LOCAL_PARSER) =
        SqlDiagnostic(DiagnosticSeverity.ERROR, "local $start", start, end, source)

    private val script = "x;\nSELECT foo bar baz qux"
    private val unit = 3 until script.length

    private fun rejectedAt(offset: Int?) = NativeOutcome.Rejected(listOf(NativeError("engine", offset)))

    @Test
    fun aPositionedRejectionDropsLocalErrorsBeforeItsFirstErrorAndKeepsLaterOnes() {
        val bar = script.indexOf("bar")
        val before = local(script.indexOf("foo"))
        val at = local(bar, source = DiagnosticSource.EDITOR_PARSER)
        val after = local(script.indexOf("qux"))
        val outcome = combineStatementDiagnostics(script, unit, listOf(before, at, after), rejectedAt(bar - 3))
        assertEquals(listOf("engine" to "bar", "local $bar" to "b", "local ${after.start}" to "q"), outcome.diagnostics.map { it.message to text(script, it) })
        assertEquals(false, outcome.nativelyAccepted)
    }

    @Test
    fun aPositionlessRejectionKeepsTheLocalErrorsAndAddsItsMessageAtTheFirst() {
        val first = local(script.indexOf("baz"), script.indexOf("baz") + 3)
        val outcome = combineStatementDiagnostics(script, unit, listOf(first), rejectedAt(null))
        assertEquals(listOf("engine" to "baz", "local ${first.start}" to "baz"), outcome.diagnostics.map { it.message to text(script, it) })
        assertEquals(listOf("engine" to "SELECT"), combineStatementDiagnostics(script, unit, listOf(), rejectedAt(null)).diagnostics.map { it.message to text(script, it) })
    }

    @Test
    fun anAcceptanceClearsOnlyLocalErrorsInsideTheVerifiedUnit() {
        val inside = local(script.indexOf("foo"))
        val outside = local(0)
        val outcome = combineStatementDiagnostics(script, unit, listOf(inside, outside), NativeOutcome.Accepted)
        assertEquals(listOf(outside), outcome.diagnostics)
        assertTrue(outcome.nativelyAccepted)
    }

    @Test
    fun noNativeVerdictKeepsTheLocalErrorsAndIsNeverClean() {
        val errors = listOf(local(script.indexOf("foo")))
        val outcome = combineStatementDiagnostics(script, unit, errors, NativeOutcome.NoVerdict("engine unavailable"))
        assertEquals(errors, outcome.diagnostics)
        assertEquals(false, outcome.nativelyAccepted)
    }

    @Test
    fun anAdvisoryEngineOnlyAddsWarnings() {
        val errors = listOf(local(script.indexOf("foo")))
        val rejected = combineStatementDiagnostics(script, unit, errors, rejectedAt(script.indexOf("qux") - 3), advisory = true)
        assertEquals(listOf(DiagnosticSeverity.WARNING to "engine", DiagnosticSeverity.ERROR to errors.single().message), rejected.diagnostics.map { it.severity to it.message })
        val accepted = combineStatementDiagnostics(script, unit, errors, NativeOutcome.Accepted, advisory = true)
        assertEquals(errors, accepted.diagnostics)
        assertEquals(false, accepted.nativelyAccepted)
    }

    @Test
    fun aLineAndColumnPlaceAnErrorWithoutAnOffset() {
        val text = "x;\nSELECT 1,\n  bad"
        val outcome = combineStatementDiagnostics(text, 3 until text.length, listOf(), NativeOutcome.Rejected(listOf(NativeError("e", line = 2, column = 3))))
        assertEquals("bad", text(text, outcome.diagnostics.single()))
    }

    @Test
    fun aWholeFileUnitFollowsTheSameRules() {
        val file = "SELECT a FROM t;\nSELECT b bad c;\nSELECT d"
        val whole = file.indices
        val early = local(file.indexOf("a "))
        val late = local(file.indexOf("d"))
        val outcome = combineStatementDiagnostics(file, whole, listOf(early, late), rejectedAt(file.indexOf("bad")))
        assertEquals(listOf("engine" to "bad", late.message to "d"), outcome.diagnostics.map { it.message to text(file, it) })
        assertEquals(listOf<SqlDiagnostic>(), combineStatementDiagnostics(file, whole, listOf(early, late), NativeOutcome.Accepted).diagnostics)
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
    fun onlyEqualDiagnosticsAreDuplicates() {
        val errors = NativeOutcome.Rejected(listOf(NativeError("a", 7), NativeError("b", 7), NativeError("a", 7)))
        assertEquals(listOf("a", "b"), combineStatementDiagnostics("SELECT x", 0 until 8, listOf(), errors).diagnostics.map { it.message })
        // A local error and an advisory warning on one range both stay.
        val sameRange = combineStatementDiagnostics("SELECT x", 0 until 8, listOf(local(7)), NativeOutcome.Rejected(listOf(NativeError("w", 7))), advisory = true)
        assertEquals(listOf(DiagnosticSeverity.WARNING to "w", DiagnosticSeverity.ERROR to "local 7"), sameRange.diagnostics.map { it.severity to it.message })
    }

    @Test
    fun crlfScriptsMapByUtf16Offsets() {
        val script = "SELECT 1;\r\nSELECT bad bad"
        val range = ranges(script)[1]
        val error = combineStatementDiagnostics(script, range, listOf(), NativeOutcome.Rejected(listOf(NativeError("e", script.lastIndexOf("bad") - range.first)))).diagnostics.single()
        assertEquals(script.lastIndexOf("bad"), error.start)
    }
}
