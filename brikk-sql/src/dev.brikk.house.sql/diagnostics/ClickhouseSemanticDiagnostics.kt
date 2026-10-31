package dev.brikk.house.sql.diagnostics

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.Schema
import dev.brikk.house.sql.optimizer.validateClickhouseCompositions
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Tokenizer

/** Stable rule names for the bounded composition/arrayMap checker, not whole-query rejection codes. */
enum class ClickhouseSemanticRule {
    VARIADIC_LEFT_DESIGNATOR, INVALID_OPERAND, MIXED_PLACEHOLDERS, PLACEHOLDER_LIMIT,
    ANONYMOUS_MEMBER, ANONYMOUS_DEPTH, RIGHT_OPERAND_ARITY, CALL_ARGUMENT_COUNT,
    ARRAY_MAP_ARITY, ARRAY_INPUT_TYPE, INPUT_TYPE,
    HIGHER_ORDER_ARGUMENT_COUNT, HIGHER_ORDER_ARITY, PREDICATE_TYPE, MAP_INPUT_TYPE, MAP_RESULT_TYPE,
}

/** Missing evidence is not a semantic error. These reasons are not editor error/warning diagnostics. */
enum class ClickhouseSemanticUnknownReason {
    ENGINE_VERSION, NAME_RESOLUTION, UNRESEARCHED_CALLABLE, INPUT_TYPE, RESULT_FAMILY,
    NULL_OR_WRAPPER, CONSTANT_ARGUMENT, TIMEZONE_VALUE, RUNTIME_RESTRICTION,
    ARRAY_LENGTHS, UNSUPPORTED_CONTEXT, PARSER_GAP, ANALYSIS_LIMIT,
    /** The requested range does not contain exactly one safely framed SQL statement. */
    STATEMENT_BOUNDARY,
}

/** [nativeErrorCode] identifies the researched rule's native category, not a server response. */
data class ClickhouseSemanticError(
    val rule: ClickhouseSemanticRule,
    val nativeErrorCode: Int,
    val diagnostic: SqlDiagnostic,
)

/** UTF-16, end-exclusive script offsets. No claim that this SQL is valid or invalid. */
data class ClickhouseSemanticUnknown(
    val reason: ClickhouseSemanticUnknownReason,
    val start: Int,
    val end: Int,
)

/**
 * Offline evidence for one statement at [start, end). Parser diagnostics are separate;
 * a parse failure prevents semantic checking. Empty lists never certify acceptance,
 * complete coverage, result types or successful execution. Only the bounded
 * higher-order/composition rules on exactly 26.10.2.103 are researched;
 * unrelated SQL is not validated.
 */
data class ClickhouseSemanticDiagnostics(
    val start: Int,
    val end: Int,
    val parseDiagnostics: List<SqlDiagnostic>,
    val errors: List<ClickhouseSemanticError>,
    val unknowns: List<ClickhouseSemanticUnknown>,
)

/**
 * Analyze one statement in authored [script], with non-empty inclusive [range]. All
 * returned positions are UTF-16 script offsets, ends exclusive. Pass an exact engine
 * version, never an inferred nearby release. Explicit [schema] facts may establish
 * input types/name resolution; generic AST type annotations are not used as evidence.
 * Does not execute SQL, contact an engine or modify an editor/AST. Cancellation
 * propagates unchanged during framing, tokenization, parsing and semantic walking.
 * Use [clickhouseScriptSemanticDiagnostics] for a whole script.
 */
fun clickhouseSemanticDiagnostics(
    script: String,
    range: IntRange,
    engineVersion: String,
    schema: Schema? = null,
    checkCanceled: () -> Unit = {},
): ClickhouseSemanticDiagnostics {
    require(!range.isEmpty() && range.first >= 0 && range.last < script.length) { "Expected a non-empty range within the script" }
    checkCanceled()
    val sql = script.substring(range.first, range.last + 1)
    val snapshot = SqlScriptSnapshot.parse(sql, "clickhouse", checkCanceled)
    val units = snapshot.statements.filter { it.hasSql }
    if (units.size != 1 || units.single().boundaryError != null) {
        return ClickhouseSemanticDiagnostics(range.first, range.last + 1,
            localParseDiagnostics(script, range, "clickhouse", checkCanceled), emptyList(),
            listOf(ClickhouseSemanticUnknown(ClickhouseSemanticUnknownReason.STATEMENT_BOUNDARY, range.first, range.last + 1)))
    }
    val dialect = Dialects.CLICKHOUSE
    val lexed = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(sql, checkCanceled)
    fun parseFailure() = ClickhouseSemanticDiagnostics(range.first, range.last + 1,
        localParseDiagnostics(script, range, "clickhouse", checkCanceled), emptyList(), emptyList())
    if (lexed.failureOffset != null) return parseFailure()
    var parserCancellation: Throwable? = null
    val parser = dialect.parser(ErrorLevel.RAISE).also {
        it.captureSourceRanges = true
        it.checkCanceled = {
            try { checkCanceled() } catch (failure: Throwable) {
                parserCancellation = failure
                throw failure
            }
        }
    }
    val expressions = try { parser.parse(lexed.tokens, sql).filterNotNull() } catch (_: ParseError) {
        parserCancellation?.let { throw it }
        return parseFailure()
    }
    if (expressions.size != 1) return ClickhouseSemanticDiagnostics(range.first, range.last + 1,
        emptyList(), emptyList(), listOf(ClickhouseSemanticUnknown(
            ClickhouseSemanticUnknownReason.STATEMENT_BOUNDARY, range.first, range.last + 1)))
    val report = validateClickhouseCompositions(expressions.single(), engineVersion, schema, checkCanceled)
    val positions = SemanticPositions(sql, range.first, checkCanceled)
    val errors = report.errors.map { error ->
        val (start, end) = positions.at(error.expression)
        ClickhouseSemanticError(ClickhouseSemanticRule.valueOf(error.rule.name), error.rule.nativeErrorCode,
            SqlDiagnostic(DiagnosticSeverity.ERROR, error.message, start, end, DiagnosticSource.OFFLINE_SEMANTIC))
    }.distinct().sortedWith(compareBy({ it.diagnostic.start }, { it.diagnostic.end }, { it.rule.name }))
    val unknowns = report.unknowns.map { unknown ->
        val (start, end) = positions.at(unknown.expression)
        ClickhouseSemanticUnknown(ClickhouseSemanticUnknownReason.valueOf(unknown.reason.name), start, end)
    }.distinct().sortedWith(compareBy({ it.start }, { it.end }, { it.reason.name }))
    return ClickhouseSemanticDiagnostics(range.first, range.last + 1, emptyList(), errors, unknowns)
}

/** Shared script framing skips trivia-only spans and never interprets inline INSERT payload as SQL. */
fun clickhouseScriptSemanticDiagnostics(
    script: String,
    engineVersion: String,
    schema: Schema? = null,
    checkCanceled: () -> Unit = {},
): List<ClickhouseSemanticDiagnostics> = SqlScriptSnapshot.parse(script, "clickhouse", checkCanceled).statements.mapNotNull {
    val range = verificationRange(script, it) ?: return@mapNotNull null
    clickhouseSemanticDiagnostics(script, range, engineVersion, schema, checkCanceled)
}

/** Prefer the offending token, then syntax/descendant evidence, then a containing syntax span. */
private class SemanticPositions(private val sql: String, private val base: Int, private val checkCanceled: () -> Unit) {
    private fun valid(start: Int?, end: Int?): Pair<Int, Int>? {
        if (start == null || end == null || start !in sql.indices || end <= start || end > sql.length) return null
        val first = if (start > 0 && sql[start].isLowSurrogate() && sql[start - 1].isHighSurrogate()) start - 1 else start
        val last = if (end < sql.length && sql[end].isLowSurrogate() && sql[end - 1].isHighSurrogate()) end + 1 else end
        return first to last
    }
    private fun token(node: Expression): Pair<Int, Int>? {
        val meta = node.metaOrNull ?: return null
        val end = (meta["end"] as? Int)?.takeIf { it < Int.MAX_VALUE }?.plus(1)
        return valid(meta["start"] as? Int, end)
    }
    private fun syntax(node: Expression): Pair<Int, Int>? = node.syntaxOrigin?.range?.let { valid(it.start, it.end) }
    fun at(node: Expression): Pair<Int, Int> {
        checkCanceled()
        var span = token(node) ?: syntax(node)
        if (span == null) {
            var first = sql.length
            var last = 0
            for (child in node.walk()) {
                checkCanceled()
                val positioned = token(child) ?: syntax(child) ?: continue
                first = minOf(first, positioned.first)
                last = maxOf(last, positioned.second)
            }
            span = valid(first, last)
        }
        var parent = node.parent
        while (span == null && parent != null) {
            checkCanceled()
            span = syntax(parent) ?: token(parent)
            parent = parent.parent
        }
        val (start, end) = span ?: (0 to sql.length)
        return base + start to base + end
    }
}
