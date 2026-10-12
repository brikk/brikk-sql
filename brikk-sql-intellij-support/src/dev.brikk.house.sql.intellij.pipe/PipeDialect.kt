package dev.brikk.house.sql.intellij.pipe

import com.intellij.database.console.session.DatabaseSession
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.SqlParameterSpan
import dev.brikk.house.sql.parser.ColumnUnit
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.maskNamedParameters
import dev.brikk.house.sql.shape.parseParameterOccurrences
import org.jetbrains.annotations.ApiStatus

/**
 * One plugin's PIPE profile. The minimum is [dialect], [label], [vetoProperty], [language],
 * [isProjectEnabled] and [ownsSession]; every other member has a working default.
 */
@ApiStatus.Experimental
public interface PipeDialect {
    /** Core dialect name, as accepted by `Dialects.forName`. */
    public val dialect: String

    /** Product name in titles, menus and settings. */
    public val label: String

    /** Registered notification group for refusals. */
    public val notificationGroup: String get() = label

    /** System property whose value `false` disables PIPE everywhere, editing included. */
    public val vetoProperty: String

    /** PIPE editor features apply to files of this language or its dialects. */
    public val language: Language

    /** The project's PIPE setting. */
    public fun isProjectEnabled(project: Project): Boolean

    /** Enables PIPE editing, never execution, in [file] regardless of the project setting. */
    public fun enablesEditing(file: PsiFile): Boolean = false

    /** Where PIPE editing is enabled, whether [file] offers the generated-SQL preview. */
    public fun enablesPreview(file: PsiFile): Boolean = true

    /** An inspection-only view: Execute is refused and run-to-stage is unavailable. */
    public fun isInspectionHost(file: PsiFile): Boolean = false

    /** Whether PIPE statements run through [session], typically decided by its DBMS. */
    public fun ownsSession(session: DatabaseSession): Boolean

    /** Native check of generated SQL, or null. */
    public val validator: NativeSqlValidator? get() = null

    /** Half-open statement ranges of [text]; claims, ownership and boundary refusals use them. */
    public fun scriptRanges(text: String): List<SqlStatementRange> = SqlScriptSnapshot.parse(text, dialect).statements

    /** Named parameters the console prompts for. Core parse/token/shape errors may propagate. */
    public fun parameterSpans(sql: String): List<SqlParameterSpan> = parseParameterOccurrences(sql, dialect)
        .mapNotNull { occurrence ->
            occurrence.name?.takeIf { occurrence.style == ParamStyle.NAMED_COLON }
                ?.let { SqlParameterSpan(it, occurrence.range.start, occurrence.range.end) }
        }

    /** [sql] with its named parameters replaced by same-length scalars, for validation before substitution. */
    public fun maskParameters(sql: String): String = maskNamedParameters(sql, dialect)

    /** Which bare first stages run-to-stage may execute. */
    public val firstStage: FirstStageRule get() = FirstStageRule.AnyQuery

    /** How the server counts columns in error positions against generated SQL. */
    public val serverColumnUnit: ColumnUnit get() = ColumnUnit.CODE_POINT

    /** The server's first column number. */
    public val serverFirstColumn: Int get() = 1

    /** Observes submissions of translated statements, or null. */
    public val submitListener: PipeSubmitListener? get() = null

    /** The SQL that editor features read from [file]; injected fragments are unescaped by default. */
    public fun pipeText(file: PsiFile): PipeText = PipeText.of(file)
}

@ApiStatus.Experimental
public enum class FirstStageRule {
    /** Any query head, e.g. `FROM t` or `SELECT a FROM t`. */
    AnyQuery,

    /** Only a FROM-first stage. */
    FromOnly,
}

/** Vetoed by `-D<vetoProperty>=false`. */
@get:ApiStatus.Experimental
public val PipeDialect.isVetoed: Boolean get() = System.getProperty(vetoProperty).equals("false", ignoreCase = true)

/** PIPE execution and editing in [project]. */
@ApiStatus.Experimental
public fun PipeDialect.isEnabled(project: Project): Boolean =
    !project.isDisposed && !project.isDefault && !isVetoed && isProjectEnabled(project)

/** PIPE editing in [file]: the project setting, or a file the profile enables. */
@ApiStatus.Experimental
public fun PipeDialect.isEditingEnabled(file: PsiFile): Boolean =
    file.language.isKindOf(language) && !isVetoed && (enablesEditing(file) || isEnabled(file.project))

/** Whether [text] holds a pipe outside literals and comments. */
@ApiStatus.Experimental
public fun PipeDialect.containsPipe(text: String): Boolean = "|>" in text && scriptRanges(text).any { it.hasPipe }

/** The statement containing [offset]; the end of the last statement still belongs to it. */
@ApiStatus.Experimental
public fun PipeDialect.statementAt(text: String, offset: Int): SqlStatementRange? = scriptRanges(text).let { ranges ->
    ranges.firstOrNull { offset >= it.start && offset < it.end } ?: ranges.lastOrNull()?.takeIf { offset == it.end }
}
