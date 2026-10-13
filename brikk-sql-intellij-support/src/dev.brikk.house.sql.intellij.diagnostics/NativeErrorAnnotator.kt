package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.Language
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.TimeoutUtil
import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.NativeError
import dev.brikk.house.sql.diagnostics.NativeOutcome
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.diagnostics.combineStatementDiagnostics
import dev.brikk.house.sql.diagnostics.distinctByRange
import dev.brikk.house.sql.diagnostics.localParseDiagnostics
import dev.brikk.house.sql.diagnostics.verificationRange
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.verify.VerifyResult
import kotlin.time.Duration
import org.jetbrains.annotations.ApiStatus

/**
 * One plugin's native error annotation. The minimum is [language], [dialect], [label] and
 * [validator]; everything a plugin did not do before is off by default.
 */
@ApiStatus.Experimental
public interface NativeErrorProfile {
    /** Files of exactly this language are annotated; its dialects are left to their own profiles. */
    public val language: Language

    /**
     * Host languages whose files' injected SQL of [language] is annotated, such as Kotlin. Register
     * the annotator for each of them as well; external annotators do not run on injected fragments.
     */
    public val injectionHosts: Set<Language> get() = emptySet()

    /** The core dialect: statement ranges and the local parser follow it. */
    public val dialect: String

    /** Starts each engine message, as "Trino" in "Trino: mismatched input". */
    public val label: String

    /** The validator for [file] (a fragment, when injected), read with its PSI before verification runs off it; null checks locally only. */
    public fun validator(file: PsiFile): NativeSqlValidator?

    /** Whether brikk-sql's parser reports errors where the engine gives no verdict. */
    public val localParser: Boolean get() = false

    /**
     * Whether this annotator reports the IDE parser's errors (PSI error elements) of [language]
     * itself, combined with the engine's verdict like the local parser's: hidden where the engine
     * accepts, replaced where it rejects. Register a [NativeErrorPsiFilter] so the platform does
     * not show them as well.
     */
    public val reportsPsiErrors: Boolean get() = false

    public val unit: VerificationUnit get() = VerificationUnit.Statement

    /** A statement (with [VerificationUnit.File], the file) longer than this, in UTF-16 units, is not checked. */
    public val sizeCap: Int? get() = null

    /** How long verification waits before asking the engine; an edit cancels the pass, so it waits for typing to pause. */
    public val debounce: Duration get() = Duration.ZERO

    /** The time one pass may spend asking the engine; later statements get no native verdict. */
    public val budget: Duration? get() = null

    /** [sql] as the engine verifies it, of the same length, such as with binds masked. */
    public fun verificationText(sql: String): String = sql

    /**
     * Diagnostics from the plugin's own check of [statement] in [script], such as a PIPE lowering
     * refusal, instead of verification and the IDE parser's errors; null verifies it. Runs off the PSI.
     */
    public fun statementDiagnostics(script: String, statement: SqlStatementRange): List<SqlDiagnostic>? = null

    /** Diagnostics read with [file]'s PSI (a fragment, when injected), such as the last server error, positioned in [script]. */
    public fun fileDiagnostics(file: PsiFile, script: String): List<SqlDiagnostic> = emptyList()

    /** The message shown for [diagnostic]. */
    public fun message(diagnostic: SqlDiagnostic): String = when (diagnostic.source) {
        DiagnosticSource.NATIVE -> "$label: ${diagnostic.message}"
        DiagnosticSource.LOCAL_PARSER -> "$label (brikk-sql): ${diagnostic.message}"
        DiagnosticSource.EDITOR_PARSER, DiagnosticSource.OTHER -> diagnostic.message
    }
}

/** What one verification covers. */
@ApiStatus.Experimental
public enum class VerificationUnit {
    /** Each statement of core's statement ranges. */
    Statement,

    /** The whole text at once, for engines that report every error of a script. */
    File,
}

/**
 * Annotates a dialect's files, and its SQL injected into [NativeErrorProfile.injectionHosts], with
 * the engine's errors. The local parsers are the first line when enabled; the engine decides where it
 * gives a verdict (core's `combineStatementDiagnostics`). A result is applied only to the document
 * revision it was computed for, and a later pass at that revision reuses it. Cancellation propagates
 * unchanged. Register a no-arg subclass as the language's (and each host's) `externalAnnotator`.
 */
@ApiStatus.Experimental
public abstract class NativeErrorAnnotator(private val profile: NativeErrorProfile) :
    ExternalAnnotator<NativeErrorAnnotator.Snapshot, NativeErrorAnnotator.Outcome>(), DumbAware {

    /** The SQL one pass checks, at one revision of the file's document: the file's own, or each injected fragment's. */
    public class Snapshot internal constructor(
        internal val stamp: Long,
        internal val sources: List<Source>,
        internal val cached: Outcome?,
    )

    /** One SQL text: [fragment] is its injected file, or null for the annotated file itself. */
    internal class Source(
        val fragment: PsiFile?,
        val script: String,
        val validator: NativeSqlValidator?,
        val fileDiagnostics: List<SqlDiagnostic>,
        val psiErrors: List<SqlDiagnostic>,
    )

    /** One pass's diagnostics per source, for the revision of its [Snapshot]. */
    public class Outcome internal constructor(
        internal val profile: NativeErrorProfile,
        internal val stamp: Long,
        internal val scripts: List<String>,
        internal val diagnostics: List<List<SqlDiagnostic>>,
    )

    // Validating matters most where the file's PSI already has errors.
    final override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Snapshot? = collectInformation(file)

    final override fun collectInformation(file: PsiFile): Snapshot? {
        val fragments = when (file.language) {
            profile.language -> listOf(null)
            in profile.injectionHosts -> injectedFragments(file).ifEmpty { return null }
            else -> return null
        }
        val stamp = PsiDocumentManager.getInstance(file.project).getDocument(file)?.modificationStamp ?: return null
        val texts = fragments.map { PipeText.of(it ?: file) }
        if (texts.all { it.text.isBlank() }) return null
        file.getUserData(OUTCOME)?.takeIf { it.profile === profile && it.stamp == stamp && it.scripts == texts.map(PipeText::text) }
            ?.let { return Snapshot(stamp, emptyList(), it) }
        val sources = fragments.zip(texts) { fragment, text ->
            val sqlFile = fragment ?: file
            Source(fragment, text.text, profile.validator(sqlFile), profile.fileDiagnostics(sqlFile, text.text),
                if (profile.reportsPsiErrors) psiErrors(text) else emptyList())
        }
        return Snapshot(stamp, sources, null)
    }

    final override fun doAnnotate(collectedInfo: Snapshot?): Outcome? {
        val snapshot = collectedInfo ?: return null
        snapshot.cached?.let { return it }
        if (profile.debounce.isPositive() && snapshot.sources.any { it.validator != null }) pause(profile.debounce)
        val deadline = profile.budget?.let { System.nanoTime() + it.inWholeNanoseconds }
        val diagnostics = snapshot.sources.map { check(it, deadline) }
        return Outcome(profile, snapshot.stamp, snapshot.sources.map(Source::script), diagnostics)
    }

    final override fun apply(file: PsiFile, annotationResult: Outcome?, holder: AnnotationHolder) {
        val outcome = annotationResult ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        // An edit while the pass ran makes its offsets wrong for the current text.
        if (document.modificationStamp != outcome.stamp) return
        val fragments = if (file.language == profile.language) listOf(null) else injectedFragments(file)
        val texts = fragments.map { PipeText.of(it ?: file) }
        if (texts.map(PipeText::text) != outcome.scripts) return
        file.putUserData(OUTCOME, outcome)
        val manager = InjectedLanguageManager.getInstance(file.project)
        val annotated = HashSet<TextRange>()
        for ((index, diagnostics) in outcome.diagnostics.withIndex()) {
            val fragment = fragments[index]
            for (diagnostic in diagnostics) {
                val local = texts[index].fileRange(diagnostic.start, diagnostic.end) ?: continue
                val range = (if (fragment == null) local else manager.injectedToHost(fragment, local)).takeIf { it.endOffset <= file.textLength && !it.isEmpty } ?: continue
                if (!annotated.add(range)) continue
                val severity = if (diagnostic.severity == DiagnosticSeverity.WARNING) HighlightSeverity.WARNING else HighlightSeverity.ERROR
                holder.newAnnotation(severity, profile.message(diagnostic)).range(range).create()
            }
        }
    }

    /** The diagnostics of one source, positioned in its script. */
    private fun check(source: Source, deadline: Long?): List<SqlDiagnostic> {
        val script = source.script
        val statements = when (profile.unit) {
            VerificationUnit.Statement -> SqlScriptSnapshot.parse(script, profile.dialect, ProgressManager::checkCanceled).statements
            VerificationUnit.File -> listOf(SqlStatementRange(0, script.length, hasSql = script.isNotBlank(), hasPipe = false))
        }
        val validator = source.validator
        val severity = if (validator?.advisory == true) DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR
        val diagnostics = ArrayList(source.fileDiagnostics)
        val psiErrors = source.psiErrors.toMutableList()
        for (statement in statements) {
            ProgressManager.checkCanceled()
            val inStatement = psiErrors.filter { it.start >= statement.start && it.start < statement.end }
            profile.statementDiagnostics(script, statement)?.let {
                diagnostics += it
                psiErrors -= inStatement.toSet()
                continue
            }
            val range = verificationRange(script, statement) ?: continue
            if (profile.sizeCap?.let { range.last + 1 - range.first > it } == true) continue
            psiErrors -= inStatement.toSet()
            val local = inStatement + if (profile.localParser) localDiagnostics(script, range) else emptyList()
            val native = when {
                validator == null -> NativeOutcome.NoVerdict("No native validator")
                deadline != null && System.nanoTime() > deadline -> NativeOutcome.NoVerdict("Verification budget spent")
                else -> nativeOutcome(validator, script.substring(range.first, range.last + 1))
            }
            diagnostics += combineStatementDiagnostics(script, range, local, native, severity).diagnostics
        }
        // The IDE parser's errors outside every checked statement have no verdict to answer them.
        return (diagnostics + psiErrors).distinctByRange()
    }

    private fun localDiagnostics(script: String, range: IntRange): List<SqlDiagnostic> =
        recoverEditorOperation { localParseDiagnostics(script, range, profile.dialect) }.getOrElse {
            LOG.warn("brikk-sql's ${profile.dialect} parser failed on a statement; reporting none for it", it)
            emptyList()
        }

    private fun nativeOutcome(validator: NativeSqlValidator, sql: String): NativeOutcome {
        val text = profile.verificationText(sql)
        if (text.length != sql.length) return NativeOutcome.NoVerdict("The verification text changed length")
        val result = recoverEditorOperation { validator.verify(text, null) }.getOrElse {
            LOG.warn("The ${profile.label} validator failed on a statement; reporting no verdict for it", it)
            return NativeOutcome.NoVerdict(it.message ?: "Validator failure")
        }
        return when (result) {
            VerifyResult.Accepted -> NativeOutcome.Accepted
            is VerifyResult.Rejected -> NativeOutcome.Rejected(result.errors.map { NativeError(it.message, it.offset, it.endOffset) })
            is VerifyResult.Unverified -> NativeOutcome.NoVerdict(result.reason)
            is VerifyResult.Unavailable -> NativeOutcome.NoVerdict(result.reason)
        }
    }

    /** The fragments of [NativeErrorProfile.language] injected into [host], in text order. */
    private fun injectedFragments(host: PsiFile): List<PsiFile> {
        val manager = InjectedLanguageManager.getInstance(host.project)
        return PsiTreeUtil.findChildrenOfType(host, PsiLanguageInjectionHost::class.java).asSequence()
            .flatMap { manager.getInjectedPsiFiles(it).orEmpty().asSequence() }
            .map { it.first.containingFile }
            .filter { it.language == profile.language }
            .distinct()
            .toList()
    }

    /** The PSI errors of [source]'s file, positioned in its (unescaped) text. */
    private fun psiErrors(source: PipeText): List<SqlDiagnostic> {
        val script = source.text
        if (script.isEmpty()) return emptyList()
        return PsiTreeUtil.findChildrenOfType(source.file, PsiErrorElement::class.java).map { error ->
            val start = source.textOffset(error.textRange.startOffset).coerceIn(0, script.length)
            val end = source.textOffset(error.textRange.endOffset).coerceIn(start, script.length)
            val (first, last) = when {
                end > start -> start to end
                start < script.length -> start to script.offsetByCodePoints(start, 1)
                else -> script.offsetByCodePoints(start, -1) to start
            }
            SqlDiagnostic(DiagnosticSeverity.ERROR, error.errorDescription, first, last, DiagnosticSource.EDITOR_PARSER)
        }
    }

    private fun pause(duration: Duration) {
        val until = System.nanoTime() + duration.inWholeNanoseconds
        while (System.nanoTime() < until) {
            ProgressManager.checkCanceled()
            TimeoutUtil.sleep(10)
        }
    }
}

/**
 * Hides the platform's highlighting of [language]'s PSI errors, injected fragments included, because a
 * [NativeErrorAnnotator] with [NativeErrorProfile.reportsPsiErrors] reports them against the engine's
 * verdict. Only PSI error elements are affected, never other inspections or annotators. Register a
 * no-arg subclass as the plugin's `highlightErrorFilter`.
 */
@ApiStatus.Experimental
public abstract class NativeErrorPsiFilter(private val language: Language) : HighlightErrorFilter() {
    final override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean =
        element.containingFile?.language != language
}

/** The last applied outcome of a file, reused by later passes at the same revision. */
private val OUTCOME = Key.create<NativeErrorAnnotator.Outcome>("brikk.sql.native.errors")

private val LOG = logger<NativeErrorAnnotator>()
