package dev.brikk.house.sql.intellij.host

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiModificationTracker
import dev.brikk.house.sql.intellij.pipe.PipeText
import java.util.concurrent.atomic.AtomicLong

/** What a recognizer of the host language decided about one host string. */
public sealed interface HostSqlClaim {
    /**
     * SQL in [entries], injected as [language]. [pending]: some entries are [HostSqlEntry.placeholder]s
     * for facts not known yet; [inspectionOnly]: the SQL is for editing, not for running as it is;
     * [colonBinds]: `:name` binds parse as parameters; [data]: the caller's own facts, for its line
     * marker tooltip.
     */
    public data class Injected(
        val language: Language,
        val entries: HostSqlEntries,
        val pending: Boolean = false,
        val inspectionOnly: Boolean = true,
        val colonBinds: Boolean = true,
        val data: Any? = null,
    ) : HostSqlClaim

    /**
     * The recognizer's facts are not available yet and it has nothing to show: the string is the
     * channel's (no other injection takes it), but nothing is injected until a retry. A recognizer with
     * a previous recognition to keep claims [Injected] with `pending` and placeholders instead.
     */
    public data class Unavailable(val reason: String) : HostSqlClaim

    /** Not SQL of this channel. */
    public data object NotClaimed : HostSqlClaim
}

/** What an injected SQL file of a channel is: see [HostSqlClaim.Injected]. [placeholders] are ranges of its unescaped text. */
public data class HostSqlMarker(
    val pending: Boolean,
    val inspectionOnly: Boolean,
    val colonBinds: Boolean,
    val placeholders: List<TextRange>,
)

/** A host string's state after its last injection: its claim, and the layout when injected. */
public data class HostSqlState(val claim: HostSqlClaim, val layout: HostSqlLayout? = null)

/**
 * One plugin's SQL in host strings: it performs a recognizer's [HostSqlClaim]s, remembers each host's
 * state and marks the injected files, schedules a retry for every unavailable or pending claim, and
 * answers what other features ask of a file. Keys belong to the instance, so plugins (and their copies
 * of this library) never see each other's state. Keep one instance per plugin; register its claims
 * through a [HostSqlInjector], the one registration that keeps other injections off claimed strings.
 */
public open class HostSqlChannel(public val id: String) {
    private val hostKey = Key.create<HostSqlState>("$id.host.sql.state")
    private val fileKey = Key.create<HostSqlMarker>("$id.host.sql.marker")
    private val retryKey = Key.create<Long>("$id.host.sql.retry")

    /**
     * Performs [claim] for [host]: injects the SQL of [HostSqlClaim.Injected] (with [prefix] before
     * the first place and [suffix] after the last), claims [HostSqlClaim.Unavailable] without
     * injecting anything and schedules a retry. Returns whether the host is claimed: a
     * [HostSqlInjector] returns it as its performer's result, which keeps every later contributor and
     * performer of the host language off the string.
     */
    public fun perform(registrar: MultiHostRegistrar, host: PsiLanguageInjectionHost, claim: HostSqlClaim, prefix: String = "", suffix: String = ""): Boolean =
        when (claim) {
            HostSqlClaim.NotClaimed -> {
                host.putUserData(hostKey, null)
                false
            }
            is HostSqlClaim.Unavailable -> {
                host.putUserData(hostKey, HostSqlState(claim))
                scheduleRetry(host)
                true
            }
            is HostSqlClaim.Injected -> {
                val layout = HostSqlInjection.layout(host, claim.entries)
                host.putUserData(hostKey, HostSqlState(claim, layout))
                val marker = HostSqlMarker(claim.pending, claim.inspectionOnly, claim.colonBinds, layout.placeholders.map { it.shiftRight(prefix.length) })
                registrar.startInjecting(claim.language)
                registrar.putInjectedFileUserData(fileKey, marker)
                // Not a Frankenstein injection: that hides every parse error, and only the placeholders' are no errors.
                registrar.makeInspectionsLenient(claim.pending || layout.placeholders.isNotEmpty())
                layout.places.forEachIndexed { index, place ->
                    registrar.addPlace(
                        (if (index == 0) prefix else "") + place.prefix,
                        place.suffix + if (index == layout.places.lastIndex) suffix else "",
                        host,
                        place.range,
                    )
                }
                registrar.doneInjecting()
                if (claim.pending) scheduleRetry(host)
                true
            }
        }

    /** [host]'s state after its last injection by this channel; null when it is not claimed. */
    public fun state(host: PsiElement): HostSqlState? = host.getUserData(hostKey)

    /** The marker of [file] when this channel injected it; also for a completion copy. */
    public fun marker(file: PsiFile?): HostSqlMarker? = file?.let { it.getUserData(fileKey) ?: it.originalFile.getUserData(fileKey) }

    /**
     * Whether a host string this channel has not decided about is still its own, so SQL another
     * injector put there is inspection-only too; a plugin asks its recognizer. False by default.
     */
    protected open fun claimsHost(host: PsiLanguageInjectionHost): Boolean = false

    /**
     * Whether [file] is SQL for editing only, never to run as it is: SQL this channel injected as
     * inspection-only or pending, or any SQL injected into a host string the channel claims (its
     * last claim, or [claimsHost]), even by another injector. PIPE Execute refuses it and
     * run-to-stage is unavailable.
     */
    public fun isInspectionOnly(file: PsiFile?): Boolean {
        file ?: return false
        marker(file)?.let { return it.inspectionOnly || it.pending }
        return hostsOf(file.originalFile).any { host -> state(host)?.claim.let { it != null && it != HostSqlClaim.NotClaimed } || claimsHost(host) }
    }

    /** Every host string [file] is injected into, in order; one injection may join several. */
    private fun hostsOf(file: PsiFile): List<PsiLanguageInjectionHost> {
        val manager = InjectedLanguageManager.getInstance(file.project)
        val first = manager.getInjectionHost(file) ?: return emptyList()
        val hosts = mutableListOf(first)
        manager.enumerate(first) { injected, places ->
            if (injected.containingFile == file) for (place in places) place.host?.let { if (it !in hosts) hosts += it }
        }
        return hosts
    }

    private val claimKey = Key.create<CachedClaim>("$id.host.sql.claim")
    private val generation = AtomicLong()

    private class CachedClaim(val revision: Long, val generation: Long, val claim: HostSqlClaim)

    /**
     * [host]'s claim from [recognize], shared by every caller (an injector's contributor and performer
     * are separate instances) until any PSI in the project changes (a constant the string interpolates
     * may live in another file) or the channel reanalyzes; negative claims included, so a string
     * recognized later is found then.
     */
    internal fun claim(host: PsiLanguageInjectionHost, recognize: () -> HostSqlClaim): HostSqlClaim {
        val revision = PsiModificationTracker.getInstance(host.project).modificationCount
        val current = generation.get()
        host.getUserData(claimKey)?.takeIf { it.revision == revision && it.generation == current }?.let { return it.claim }
        return recognize().also { host.putUserData(claimKey, CachedClaim(revision, current, it)) }
    }

    /** Whether [file] waits for facts; relation diagnostics do not run then. */
    public fun isPending(file: PsiFile?): Boolean = marker(file)?.pending == true

    /** Whether `:name` binds parse as parameters in [file]; a parser calls `parseColonParameter()` when they do. */
    public fun parsesColonBinds(file: PsiFile?): Boolean = marker(file)?.colonBinds == true

    /**
     * Whether an error at `[start, end)` of [text], [file]'s unescaped text, is a placeholder's: a
     * non-empty one overlapping a placeholder, or an empty one before a placeholder's text (the token
     * it complains about).
     */
    public fun isPlaceholderError(file: PsiFile?, text: String, start: Int, end: Int): Boolean {
        val placeholders = marker(file)?.placeholders.orEmpty().ifEmpty { return false }
        if (end > start) return placeholders.any { it.startOffset < end && start < it.endOffset }
        var token = start
        while (token < text.length && text[token].isWhitespace()) token++
        return placeholders.any { token >= it.startOffset && token < it.endOffset }
    }

    /**
     * Schedules one reanalysis of [host]'s file once indexing is done (see [reanalyze]). At most one
     * per file and file revision, so a reanalysis that claims the same string unavailable again does
     * not loop; facts that change without an edit call [requestReanalysis].
     */
    public fun scheduleRetry(host: PsiElement) {
        val file = host.containingFile ?: return
        val revision = file.modificationStamp
        if (file.getUserData(retryKey) == revision) return
        file.putUserData(retryKey, revision)
        later(file)
    }

    /**
     * Reanalyzes [file] (a host file, or SQL injected into one) now, or once indexing is done: for a
     * provider that learns its facts changed without an edit, such as shapes generated later. Never
     * call it while computing facts (a relation provider's `facts`, a claim): it would
     * reanalyze again and again.
     */
    public fun requestReanalysis(file: PsiFile) {
        later(InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file) ?: file)
    }

    private fun later(file: PsiFile) {
        val project = file.project
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(file)
        ApplicationManager.getApplication().invokeLater({
            DumbService.getInstance(project).runWhenSmart {
                runReadAction {
                    val current = pointer.element ?: return@runReadAction
                    // Every cached claim is asked again: the facts behind it may have changed.
                    generation.incrementAndGet()
                    reanalyze(current)
                }
            }
        }, project.disposed)
    }

    /**
     * Recomputes [file]'s injections and restarts its highlighting. Injection results are cached
     * until PSI changes, so the PSI caches are dropped, not only the file's injected documents.
     */
    protected open fun reanalyze(file: PsiFile) {
        PsiManager.getInstance(file.project).dropPsiCaches()
        InjectedLanguageManager.getInstance(file.project).dropFileCaches(file)
        DaemonCodeAnalyzer.getInstance(file.project).restart(file, "$id host SQL reanalysis")
    }
}

/**
 * Hides parse errors on the placeholders of [channel]'s SQL: they stand for text not known yet.
 * Register a no-arg subclass as a `highlightErrorFilter`.
 */
public abstract class HostSqlPlaceholderErrorFilter(private val channel: HostSqlChannel) : HighlightErrorFilter() {
    final override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val file = element.containingFile ?: return true
        if (channel.marker(file)?.placeholders.isNullOrEmpty()) return true
        val text = PipeText.of(file)
        return !channel.isPlaceholderError(file, text.text, text.textOffset(element.textRange.startOffset), text.textOffset(element.textRange.endOffset))
    }
}
