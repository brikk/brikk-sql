package dev.brikk.house.sql.scope

import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.dialects.Dialects
import kotlinx.serialization.Serializable

/**
 * A column as SQL sees it at one place: its current name, the relation it is visible through,
 * and the fact it passes through unchanged ([source]), if any.
 *
 * - [relation] is the alias, table, slot or CTE name that qualifies it, empty when nothing
 *   does: a projection's output, a pipe stage's output, a merged USING key.
 * - [origin] is the source fact's origin, or [ColumnOrigin.COMPUTED].
 * - [computedBy] names what computes a computed column: a pipe stage (`EXTEND`), a set
 *   operation (`UNION ALL`), `PIVOT`, `USING`, or the CTE or derived table whose projection it is
 *   (`SELECT` when it has no name).
 * - [provenance] is the source fact's navigation id, kept through renames.
 * - [declaration] is where SQL names the column (`AS k`, a CTE or alias column list, a RENAME
 *   target), kept through later references; null when only a fact names it.
 */
@Serializable
data class ScopeColumn(
    val name: String,
    val relation: String,
    val origin: ColumnOrigin,
    val nullability: Nullability,
    val sqlType: String? = null,
    val displayType: String? = null,
    val source: ColumnSource? = null,
    val computedBy: String? = null,
    val provenance: String? = null,
    val declaration: ScopeRange? = null,
)

/** A range `[start, end)` of script offsets. */
@Serializable
data class ScopeRange(val start: Int, val end: Int)

/** The [column] fact supplied under [relation], a slot, table or call key. */
@Serializable
data class ColumnSource(val relation: String, val column: ColumnFact)

@Serializable
enum class RelationKind { SLOT, TABLE, CALL, CTE, SUBQUERY, FUNCTION, VALUES, OTHER }

/**
 * A relation a qualifier or table name refers to. [source] is its slot, table, call or CTE name;
 * [declaration] is where SQL names it: its alias, or a CTE's name.
 */
@Serializable
data class ScopeRelation(
    val name: String,
    val kind: RelationKind,
    val knowledge: Knowledge,
    val source: String? = null,
    val provenance: String? = null,
    val declaration: ScopeRange? = null,
)

@Serializable
enum class ReferenceKind { COLUMN, QUALIFIER, TABLE }

@Serializable
enum class Resolution {
    RESOLVED,
    AMBIGUOUS,

    /** Nothing matched; [ScopeReference.knowledge] says whether that proves absence. */
    UNRESOLVED,
}

/**
 * One name in the SQL, `[start, end)`, and what it refers to: for a column the target, or the
 * candidates when [Resolution.AMBIGUOUS]; for a qualifier or table the relation. [knowledge] is
 * how much the scope searched knew.
 */
@Serializable
data class ScopeReference(
    val kind: ReferenceKind,
    val name: String,
    val start: Int,
    val end: Int,
    val resolution: Resolution,
    val knowledge: Knowledge,
    val columns: List<ScopeColumn> = emptyList(),
    val relation: ScopeRelation? = null,
)

@Serializable
enum class ScopeProblem(val severity: DiagnosticSeverity) {
    /** A column absent from fully known relations. */
    MISSING_COLUMN(DiagnosticSeverity.ERROR),

    /** A column absent from what a partial contract lists; it may exist at run time. */
    PARTIAL_CONTRACT(DiagnosticSeverity.WARNING),
    AMBIGUOUS_COLUMN(DiagnosticSeverity.ERROR),
    UNKNOWN_QUALIFIER(DiagnosticSeverity.ERROR),

    /** A table absent from a [CatalogKnowledge.CLOSED] catalog. */
    UNKNOWN_TABLE(DiagnosticSeverity.ERROR),

    /** Positional set-operation branches with different column counts; marks the operator. */
    COLUMN_COUNT_MISMATCH(DiagnosticSeverity.ERROR),

    /** A pipe stage execution cannot lower (a slice that is no literal, a qualified RENAME); marks the stage. */
    PIPE_UNSUPPORTED(DiagnosticSeverity.ERROR),
}

/** A problem over `[start, end)`, never empty; [toSqlDiagnostic] gives the editor-wide form. */
@Serializable
data class ScopeDiagnostic(
    val problem: ScopeProblem,
    val severity: DiagnosticSeverity,
    val message: String,
    val start: Int,
    val end: Int,
) {
    init {
        require(start in 0 until end) { "A diagnostic needs a non-empty range: [$start, $end)" }
    }

    /** This diagnostic among the others an editor shows, from [DiagnosticSource.SCOPE]. */
    fun toSqlDiagnostic(): SqlDiagnostic = SqlDiagnostic(severity, message, start, end, DiagnosticSource.SCOPE)
}

@Serializable
enum class PositionKind { COLUMN, QUALIFIER, RELATION, STRING, COMMENT, OTHER }

@Serializable
enum class CompletionKind { COLUMN, RELATION }

/**
 * A completion candidate that replaces `[start, end)` with [insertText]. [detail] says what it is,
 * [type] a column's type. A COLUMN item carries its [column], a RELATION item its [relation].
 */
@Serializable
data class CompletionItem(
    val name: String,
    val kind: CompletionKind,
    val insertText: String,
    val start: Int,
    val end: Int,
    val detail: String,
    val type: String?,
    val column: ScopeColumn? = null,
    val relation: ScopeRelation? = null,
)

/**
 * What a caret can complete: the [position] it is in, and at a column position the columns
 * visible there, at a relation position the CTEs in scope and the facts' tables, slots and table
 * functions ([items], all of them: clients filter by [prefix], the name typed before the
 * caret without its opening quote) and how much the scope knew. Items replace the whole
 * identifier at the caret, `[start, end)`, quotes included. [knowledge] is null at other positions.
 */
@Serializable
data class ScopeCompletion(
    val position: PositionKind,
    val knowledge: Knowledge?,
    val start: Int,
    val end: Int,
    val prefix: String,
    val items: List<CompletionItem>,
)

/**
 * brikk-native: the relation scope of SQL statements, for editors (IntelliJ, an LSP server) and
 * any tool that needs to know what a name in SQL refers to. Pure data in, pure data out: a
 * function of the text, the dialect and the copied facts, with no shared state.
 *
 * Offsets are UTF-16 offsets into the analyzed script, half-open; `range` is the region to
 * analyze (a statement of a script, a dialect region of a host document), and results stay in
 * the script's coordinates. PIPE stages scope as brikk-sql lowers them for execution
 * (ast/PipeDesugar.kt): projection-changing stages drop old qualifiers, `AS` replaces them, and a
 * qualifier execution cannot keep across a stage boundary does not resolve. Only queries are
 * analyzed, including those inside other statements (INSERT ... SELECT).
 *
 * Experimental: the shape of these types may change until the IntelliJ and LSP consumers use it.
 */
@Serializable
data class RelationScope(
    val references: List<ScopeReference>,
    val diagnostics: List<ScopeDiagnostic>,
    /** The statement's result as a fact other SQL can use, or null unless the region is one query. */
    val output: RelationFact?,
    /** False when the region does not parse; there is nothing else then. */
    val parsed: Boolean,
) {
    /** The innermost reference covering [offset], or the one ending there. */
    fun referenceAt(offset: Int): ScopeReference? =
        references.filter { offset >= it.start && offset < it.end }.minByOrNull { it.end - it.start }
            ?: references.filter { offset == it.end }.minByOrNull { it.end - it.start }

    companion object {
        /**
         * Analyzes the SQL at [range] of [script] in [dialect] (a name [Dialects.forName]
         * accepts). [checkCanceled] is called periodically; whatever it throws propagates.
         */
        fun analyze(
            script: String,
            dialect: String,
            facts: RelationFacts,
            range: IntRange = script.indices,
            checkCanceled: () -> Unit = {},
        ): RelationScope {
            require(range.isEmpty() || range.first >= 0 && range.last < script.length) { "Range $range is outside the script" }
            return ScopeAnalysis(script, range, Dialects.forName(dialect), facts, checkCanceled).analyze()
        }

        /**
         * The completion at [offset] in the SQL at [range] of [script], recovering from
         * incomplete input around it: the text need not parse.
         */
        fun complete(
            script: String,
            dialect: String,
            facts: RelationFacts,
            offset: Int,
            range: IntRange = script.indices,
            checkCanceled: () -> Unit = {},
        ): ScopeCompletion {
            require(range.isEmpty() || range.first >= 0 && range.last < script.length) { "Range $range is outside the script" }
            require(offset in range.first..range.last + 1) { "Offset $offset is outside $range" }
            return CaretCompletion(script, range, offset, Dialects.forName(dialect), facts, checkCanceled).complete()
        }
    }
}
