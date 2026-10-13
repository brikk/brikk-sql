package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.Language
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.TimeoutUtil
import com.intellij.util.concurrency.AppExecutorUtil
import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.NativeError
import dev.brikk.house.sql.diagnostics.NativeOutcome
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.diagnostics.combineStatementDiagnostics
import dev.brikk.house.sql.diagnostics.localParseDiagnostics
import dev.brikk.house.sql.diagnostics.verificationRange
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.verify.VerifyResult
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * How long verification waits before asking the engine; an edit cancels the pass, so it waits
     * for typing to pause. The platform runs a file's external annotators one after another, so the
     * waits of several consumers on one file add up: keep it [Duration.ZERO] unless asking is costly.
     */
    public val debounce: Duration get() = Duration.ZERO

    /**
     * The time one pass may spend asking the engine. A verification still running when it ends is
     * canceled (or abandoned, if it ignores cancellation), and that statement and the later ones get
     * no native verdict, so their local results show; the next pass asks again. A validator answers
     * one call at a time: while an abandoned call still runs, later passes show local results too.
     */
    public val budget: Duration? get() = null

    /** [sql] as the engine verifies it, of the same length, such as with binds masked. */
    public fun verificationText(sql: String): String = sql

    /**
     * Diagnostics from the plugin's own check of [statement] in [script], such as a PIPE lowering
     * refusal, instead of verification and the IDE parser's errors; null verifies it. Runs off the PSI.
     */
    public fun statementDiagnostics(script: String, statement: SqlStatementRange): List<SqlDiagnostic>? = null

    /**
     * Diagnostics read with [file]'s PSI (a fragment, when injected), such as the last server error,
     * positioned in [script]. Read again whenever results are applied, so they change without an edit.
     */
    public fun fileDiagnostics(file: PsiFile, script: String): List<SqlDiagnostic> = emptyList()

    /**
     * A count that changes whenever anything but [file]'s text can change its verdicts: settings, a
     * connection, a newly available engine or data source. A result is reused only while it, the
     * text and [validator]'s instance stay the same. Restart the daemon for the file when it changes,
     * so that a pass runs.
     */
    public fun modificationCount(file: PsiFile): Long = 0

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

    /** What a source's verdicts depend on besides the document revision; the validator counts by instance. */
    internal data class Dependencies(val script: String, val validator: NativeSqlValidator?, val revision: Long)

    /** One SQL text: [fragment] is its injected file, or null for the annotated file itself. */
    internal class Source(
        val fragment: PsiFile?,
        val dependencies: Dependencies,
        val psiErrors: List<SqlDiagnostic>,
    ) {
        val script: String get() = dependencies.script
        val validator: NativeSqlValidator? get() = dependencies.validator
    }

    /**
     * One pass's diagnostics per source, for the revision of its [Snapshot]. It is [reusable] when
     * every verdict is final: none was left out for an unavailable engine, a failure or the budget.
     */
    public class Outcome internal constructor(
        internal val profile: NativeErrorProfile,
        internal val stamp: Long,
        internal val dependencies: List<Dependencies>,
        internal val diagnostics: List<List<SqlDiagnostic>>,
        internal val reusable: Boolean,
    )

    /** Verifications run here, a few at a time, never on the highlighting thread. */
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("${profile.label} native verification", VERIFIERS)

    /** Validators, by instance, with a call running; an abandoned call keeps its validator here until it exits. */
    private val running: MutableSet<NativeSqlValidator> = Collections.newSetFromMap(Collections.synchronizedMap(IdentityHashMap()))

    /** One profile's key, so consumers on one host file keep their own results. */
    private val outcomeKey: Key<Outcome> = OUTCOME_KEYS.computeIfAbsent(profile.language.id) { Key.create("brikk.sql.native.errors.$it") }

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
        val dependencies = dependencies(file, fragments, texts)
        file.getUserData(outcomeKey)?.takeIf { it.profile === profile && it.stamp == stamp && it.dependencies == dependencies }
            ?.let { return Snapshot(stamp, emptyList(), it) }
        val sources = fragments.indices.map { index ->
            Source(fragments[index], dependencies[index], if (profile.reportsPsiErrors) psiErrors(texts[index]) else emptyList())
        }
        return Snapshot(stamp, sources, null)
    }

    final override fun doAnnotate(collectedInfo: Snapshot?): Outcome? {
        val snapshot = collectedInfo ?: return null
        snapshot.cached?.let { return it }
        if (profile.debounce.isPositive() && snapshot.sources.any { it.validator != null }) pause(profile.debounce)
        val deadline = profile.budget?.let { System.nanoTime() + it.inWholeNanoseconds }
        val checks = snapshot.sources.map { check(it, deadline) }
        return Outcome(profile, snapshot.stamp, snapshot.sources.map(Source::dependencies), checks.map { it.diagnostics }, checks.all { it.final })
    }

    final override fun apply(file: PsiFile, annotationResult: Outcome?, holder: AnnotationHolder) {
        val outcome = annotationResult ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        // An edit while the pass ran makes its offsets wrong for the current text.
        if (document.modificationStamp != outcome.stamp) return
        val fragments = if (file.language == profile.language) listOf(null) else injectedFragments(file)
        val texts = fragments.map { PipeText.of(it ?: file) }
        // So does a settings, connection or validator change: the restart it needs only schedules a new pass.
        if (dependencies(file, fragments, texts) != outcome.dependencies) return
        file.putUserData(outcomeKey, outcome.takeIf { it.reusable })
        val manager = InjectedLanguageManager.getInstance(file.project)
        val annotated = HashSet<Triple<TextRange, DiagnosticSeverity, String>>()
        for ((index, diagnostics) in outcome.diagnostics.withIndex()) {
            val fragment = fragments[index]
            for (diagnostic in diagnostics + profile.fileDiagnostics(fragment ?: file, texts[index].text)) {
                val local = texts[index].fileRange(diagnostic.start, diagnostic.end) ?: editableRange(texts[index], diagnostic.start) ?: continue
                val range = (if (fragment == null) local else manager.injectedToHost(fragment, local)).takeIf { it.endOffset <= file.textLength && !it.isEmpty } ?: continue
                val message = profile.message(diagnostic)
                if (!annotated.add(Triple(range, diagnostic.severity, message))) continue
                val severity = if (diagnostic.severity == DiagnosticSeverity.WARNING) HighlightSeverity.WARNING else HighlightSeverity.ERROR
                holder.newAnnotation(severity, message).range(range).create()
            }
        }
    }

    private fun dependencies(file: PsiFile, fragments: List<PsiFile?>, texts: List<PipeText>): List<Dependencies> =
        fragments.zip(texts) { fragment, text ->
            val sqlFile = fragment ?: file
            Dependencies(text.text, profile.validator(sqlFile), profile.modificationCount(sqlFile))
        }

    /** A source's diagnostics, positioned in its script, and whether every verdict in them is final. */
    private class Check(val diagnostics: List<SqlDiagnostic>, val final: Boolean)

    /** The diagnostics of one source. */
    private fun check(source: Source, deadline: Long?): Check {
        val script = source.script
        val statements = when (profile.unit) {
            VerificationUnit.Statement -> SqlScriptSnapshot.parse(script, profile.dialect, ProgressManager::checkCanceled).statements
            VerificationUnit.File -> listOf(SqlStatementRange(0, script.length, hasSql = script.isNotBlank(), hasPipe = false))
        }
        val validator = source.validator
        val diagnostics = ArrayList<SqlDiagnostic>()
        val psiErrors = source.psiErrors.toMutableList()
        var final = true
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
            val asked = when {
                validator == null -> Asked(NativeOutcome.NoVerdict("No native validator"), final = true)
                deadline != null && System.nanoTime() > deadline -> Asked(NativeOutcome.NoVerdict("Verification budget spent"), final = false)
                else -> ask(validator, script.substring(range.first, range.last + 1), deadline)
            }
            final = final && asked.final
            val native = asked.outcome
            diagnostics += combineStatementDiagnostics(script, range, local, native, validator?.advisory == true).diagnostics
        }
        // The IDE parser's errors outside every checked statement have no verdict to answer them.
        return Check((diagnostics + psiErrors).distinct(), final)
    }

    private fun localDiagnostics(script: String, range: IntRange): List<SqlDiagnostic> =
        recoverEditorOperation { localParseDiagnostics(script, range, profile.dialect, ProgressManager::checkCanceled) }.getOrElse {
            LOG.warn("brikk-sql's ${profile.dialect} parser failed on a statement; reporting none for it", it)
            emptyList()
        }

    /** An engine's [outcome]; not [final] when asking again could give a verdict (an unavailable engine, a failure, the budget). */
    private class Asked(val outcome: NativeOutcome, val final: Boolean)

    private fun ask(validator: NativeSqlValidator, sql: String, deadline: Long?): Asked {
        val text = profile.verificationText(sql)
        if (text.length != sql.length) return Asked(NativeOutcome.NoVerdict("The verification text changed length"), final = true)
        // One call per validator instance: a call an earlier pass gave up on may still be running.
        if (!running.add(validator)) return Asked(NativeOutcome.NoVerdict("An earlier verification is still running"), final = false)
        val attempt = runOnce(deadline, release = { running.remove(validator) }) { validator.verify(text, null) }
        val result = (attempt ?: return Asked(NativeOutcome.NoVerdict("Verification budget spent"), final = false)).getOrElse {
            LOG.warn("The ${profile.label} validator failed on a statement; reporting no verdict for it", it)
            return Asked(NativeOutcome.NoVerdict(it.message ?: "Validator failure"), final = false)
        }
        return when (result) {
            VerifyResult.Accepted -> Asked(NativeOutcome.Accepted, final = true)
            is VerifyResult.Rejected ->
                Asked(NativeOutcome.Rejected(result.errors.map { NativeError(it.message, it.offset, it.endOffset, it.line, it.column) }), final = true)
            is VerifyResult.Unverified -> Asked(NativeOutcome.NoVerdict(result.reason), final = true)
            is VerifyResult.Unavailable -> Asked(NativeOutcome.NoVerdict(result.reason), final = false)
        }
    }

    /**
     * [operation] run on [executor] under its own indicator, or null when [deadline] passes first.
     * Giving up (at the deadline, or when the pass is canceled, which then propagates) cancels the
     * indicator; an operation that ignores it runs on unread, and [release] runs only when it exits.
     * Failures come back as in [recoverEditorOperation]; fatal errors and cancellation are rethrown.
     */
    private fun <T> runOnce(deadline: Long?, release: () -> Unit, operation: () -> T): Result<T>? {
        val indicator = EmptyProgressIndicator()
        val abandoned = AtomicBoolean()
        val future = try {
            executor.submit(Callable {
                try {
                    ProgressManager.getInstance().runProcess(Computable {
                        // Given up on before it started: the indicator's start cleared the cancellation.
                        if (abandoned.get()) throw ProcessCanceledException()
                        operation()
                    }, indicator)
                } finally {
                    release()
                }
            })
        } catch (e: Throwable) {
            release()
            throw e
        }
        fun abandon() {
            abandoned.set(true)
            indicator.cancel()
        }
        try {
            while (true) {
                ProgressManager.checkCanceled()
                val remaining = deadline?.let { it - System.nanoTime() } ?: WAIT_STEP_NANOS
                if (remaining <= 0) {
                    abandon()
                    return null
                }
                try {
                    return Result.success(future.get(minOf(remaining, WAIT_STEP_NANOS), TimeUnit.NANOSECONDS))
                } catch (_: TimeoutException) {
                    continue
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    if (cause is Error || cause is ProcessCanceledException || cause is CancellationException) throw cause
                    return Result.failure(cause)
                }
            }
        } catch (e: Throwable) {
            abandon()
            throw e
        }
    }

    /**
     * For an error at [start] of [source]'s text that lies wholly outside the editable parts of its
     * injected fragment, such as in a prefix, suffix or template hole: one code point of the nearest
     * editable part, the following one, else the last. Never drops the error while an editable part exists.
     */
    private fun editableRange(source: PipeText, start: Int): TextRange? {
        val fragment = source.file
        val manager = InjectedLanguageManager.getInstance(fragment.project)
        if (!manager.isInjectedFragment(fragment)) return null
        val point = manager.mapUnescapedOffsetToInjected(fragment, start)
        val editable = manager.intersectWithAllEditableFragments(fragment, TextRange(0, fragment.textLength)).filterNot { it.isEmpty }
        val text = fragment.text
        editable.firstOrNull { it.startOffset >= point }?.let { following ->
            return TextRange(following.startOffset, minOf(text.offsetByCodePoints(following.startOffset, 1), following.endOffset))
        }
        val last = editable.lastOrNull() ?: return null
        return TextRange(maxOf(text.offsetByCodePoints(last.endOffset, -1), last.startOffset), last.endOffset)
    }

    /** The fragments of [NativeErrorProfile.language] injected into [host], in text order, Frankenstein ones left out. */
    private fun injectedFragments(host: PsiFile): List<PsiFile> {
        val manager = InjectedLanguageManager.getInstance(host.project)
        return PsiTreeUtil.findChildrenOfType(host, PsiLanguageInjectionHost::class.java).asSequence()
            .flatMap { manager.getInjectedPsiFiles(it).orEmpty().asSequence() }
            .map { it.first.containingFile }
            // A Frankenstein fragment, such as a template with uncertain holes, is not SQL as written.
            .filter { it.language == profile.language && !manager.isFrankensteinInjection(it) }
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
 * Hides the platform's highlighting of the PSI errors a [NativeErrorAnnotator] over [profile] reports
 * itself (see [NativeErrorProfile.reportsPsiErrors]): in files of exactly the profile's language,
 * and in its fragments injected into [NativeErrorProfile.injectionHosts], Frankenstein fragments
 * excepted. Everything else keeps the platform's handling, as do other inspections and annotators.
 * The match is exact, unlike `SubstrateErrorFilter`'s `isKindOf`, because the annotator reports only
 * for exactly its language: a plugin turning `reportsPsiErrors` on replaces its `SubstrateErrorFilter`
 * with this filter rather than keeping both, and gives each derived language its own profile.
 * Register a no-arg subclass as the plugin's `highlightErrorFilter`.
 */
@ApiStatus.Experimental
public abstract class NativeErrorPsiFilter(private val profile: NativeErrorProfile) : HighlightErrorFilter() {
    final override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        if (!profile.reportsPsiErrors) return true
        val file = element.containingFile ?: return true
        if (file.language != profile.language) return true
        val manager = InjectedLanguageManager.getInstance(file.project)
        if (!manager.isInjectedFragment(file)) return false
        if (manager.isFrankensteinInjection(file)) return true
        val host = manager.getTopLevelFile(file)?.language ?: return true
        return host == profile.language || host !in profile.injectionHosts
    }
}

private const val WAIT_STEP_NANOS = 10_000_000L

private const val VERIFIERS = 2

/** Per profile language: a file's last final outcome, reused by later passes while its dependencies stay. */
private val OUTCOME_KEYS = ConcurrentHashMap<String, Key<NativeErrorAnnotator.Outcome>>()

private val LOG = logger<NativeErrorAnnotator>()
