package dev.brikk.house.sql.diagnostics

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.parser.Tokenizer

/**
 * brikk-native: editor diagnostics for SQL scripts as plain data, so any editor (IntelliJ, an LSP
 * server) shows the same results. brikk-sql's own parser is the first, offline line; an engine's
 * native verdict is an extra round for its gaps (see [combineStatementDiagnostics]).
 */
enum class DiagnosticSeverity { ERROR, WARNING }

/** Which parser a diagnostic comes from. */
enum class DiagnosticSource {
    /** brikk-sql's parser for the dialect: offline and immediate, but it may reject what the engine accepts. */
    LOCAL_PARSER,

    /** The engine's own parser or server. */
    NATIVE,

    /** A consumer's own check, such as a PIPE lowering refusal. */
    OTHER,
}

/** One diagnostic over a script; [start] and [end] are UTF-16 offsets into the script, [end] exclusive and after [start]. */
data class SqlDiagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val start: Int,
    val end: Int,
    val source: DiagnosticSource,
) {
    init {
        require(start in 0 until end) { "A diagnostic needs a non-empty range: [$start, $end)" }
    }
}

/** The engine's check of one statement. */
sealed interface NativeOutcome {
    data object Accepted : NativeOutcome

    /** [errors] are positioned in the verified statement text. */
    data class Rejected(val errors: List<NativeError>) : NativeOutcome {
        init {
            require(errors.isNotEmpty()) { "A rejection needs at least one error" }
        }
    }

    /** No verdict: the engine is unavailable, could not check this SQL, or was not asked. Never clean. */
    data class NoVerdict(val reason: String) : NativeOutcome
}

/** One engine error; [offset] and [endOffset] are UTF-16 offsets in the verified statement text, null when unknown. */
data class NativeError(val message: String, val offset: Int? = null, val endOffset: Int? = null)

/**
 * A statement's diagnostics, and whether the engine accepted it. An accepted statement keeps no
 * local parser errors, and an editor may also hide its own local parser's errors in
 * `[start, end)`; it must not hide anything else.
 */
data class StatementDiagnostics(
    val start: Int,
    val end: Int,
    val diagnostics: List<SqlDiagnostic>,
    val nativelyAccepted: Boolean,
)

/**
 * The text an engine verifies for [statement] of [script]: from its start (leading comments kept,
 * so offsets need only the statement's start) to its terminator, without trailing whitespace.
 * Null when the statement holds no SQL.
 */
fun verificationRange(script: String, statement: SqlStatementRange): IntRange? {
    if (!statement.hasSql) return null
    var end = (statement.terminator ?: statement.end).coerceIn(statement.start, script.length)
    while (end > statement.start && script[end - 1].isWhitespace()) end--
    return if (end > statement.start) statement.start until end else null
}

/**
 * brikk-sql's parser errors for the SQL at [range] of [script] in [dialect], positioned in the
 * script. A lexical error marks where its token begins; an unknown position marks the first
 * non-blank character. Empty for a dialect brikk-sql does not know.
 */
fun localParseDiagnostics(script: String, range: IntRange, dialect: String): List<SqlDiagnostic> {
    val source = Dialects.forNameOrNull(dialect) ?: return emptyList()
    val sql = script.substring(range.first, range.last + 1)
    val lexed = Tokenizer(source.tokenizerConfig).tokenizeForEditor(sql)
    lexed.failureOffset?.let { failure ->
        return listOf(diagnostic(script, range, DiagnosticSource.LOCAL_PARSER, DiagnosticSeverity.ERROR, lexed.error ?: "Invalid token", failure, null))
    }
    return try {
        source.parser(ErrorLevel.RAISE).parse(lexed.tokens, sql)
        emptyList()
    } catch (e: ParseError) {
        val errors = e.errors.ifEmpty { null }
            ?: return listOf(diagnostic(script, range, DiagnosticSource.LOCAL_PARSER, DiagnosticSeverity.ERROR, e.message ?: "Invalid SQL", null, null))
        errors.map { error ->
            diagnostic(script, range, DiagnosticSource.LOCAL_PARSER, DiagnosticSeverity.ERROR, error.description ?: e.message ?: "Invalid SQL", error.start, error.end)
        }.distinctByRange()
    }
}

/**
 * Combines [local] parser diagnostics with the engine's [native] outcome for the SQL verified at
 * [range] of [script]: a rejection shows the engine's errors (in [nativeSeverity]) instead of the
 * local ones, an acceptance clears the local ones as false positives, and no verdict leaves the
 * local ones alone.
 */
fun combineStatementDiagnostics(
    script: String,
    range: IntRange,
    local: List<SqlDiagnostic>,
    native: NativeOutcome,
    nativeSeverity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
): StatementDiagnostics {
    val diagnostics = when (native) {
        NativeOutcome.Accepted -> emptyList()
        is NativeOutcome.Rejected -> native.errors.map {
            diagnostic(script, range, DiagnosticSource.NATIVE, nativeSeverity, it.message, it.offset, it.endOffset)
        }
        is NativeOutcome.NoVerdict -> local
    }
    return StatementDiagnostics(range.first, range.last + 1, diagnostics.distinctByRange(), native == NativeOutcome.Accepted)
}

/** One diagnostic per range, the first kept, so two sources never mark the same text twice. */
fun List<SqlDiagnostic>.distinctByRange(): List<SqlDiagnostic> = distinctBy { it.start to it.end }

/**
 * A diagnostic for an error at [offset] in the SQL at [range] of [script]: up to [endOffset] when
 * known and after it, else across the identifier there, else one character. An offset at or past
 * the end marks the last character; an unknown one, the first non-blank character.
 */
private fun diagnostic(
    script: String,
    range: IntRange,
    source: DiagnosticSource,
    severity: DiagnosticSeverity,
    message: String,
    offset: Int?,
    endOffset: Int?,
): SqlDiagnostic {
    val start = range.first
    val end = range.last + 1
    val first = when {
        offset == null || offset < 0 -> (start until end).firstOrNull { !script[it].isWhitespace() } ?: start
        start + offset >= end -> script.offsetByCodePoints(end, -1)
        else -> codePointStart(script, start + offset)
    }
    val last = endOffset?.let { start + it }?.takeIf { it > first && it <= end }
        ?: (first until end).firstOrNull { !script[it].isLetterOrDigit() && script[it] != '_' }?.takeIf { it > first }
        ?: if (first < end && script[first].isLetterOrDigit()) end else script.offsetByCodePoints(first, 1)
    return SqlDiagnostic(severity, message, first, last, source)
}

/** [offset], moved back to the start of the code point it falls inside. */
private fun codePointStart(text: String, offset: Int): Int =
    if (offset > 0 && offset < text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset - 1 else offset
