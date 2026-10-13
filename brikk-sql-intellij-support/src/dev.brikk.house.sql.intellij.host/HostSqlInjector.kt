package dev.brikk.house.sql.intellij.host

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.lang.injection.general.LanguageInjectionPerformer
import com.intellij.lang.injection.general.SimpleInjection
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import org.jetbrains.annotations.ApiStatus

/**
 * A recognizer's claims, registered the way that keeps other injections off claimed strings: the
 * host language's injection contributor and performer, both `order="first"`. The platform injects a
 * string with the first contributor's injection and the first performer that performs it, so a
 * claimed string, injected or [HostSqlClaim.Unavailable], gets no other contributor's SQL (an
 * `@Language` annotation, a configured injection), indexing or not: this class is dumb-aware.
 *
 * Register a no-arg subclass under both `languageInjectionContributor` and
 * `languageInjectionPerformer` for the host language. Injectors outside that mechanism (a
 * `multiHostInjector`) cannot be kept off; SQL they inject into a claimed string is still
 * inspection-only ([HostSqlChannel.isInspectionOnly]).
 */
@ApiStatus.Experimental
public abstract class HostSqlInjector(
    private val channel: HostSqlChannel,
    /** The language of the SQL; the injection of an unavailable claim names it too. */
    private val language: Language,
) : LanguageInjectionContributor, LanguageInjectionPerformer, DumbAware {
    /** The recognizer's claim for [host]. Asked once per host and file revision. */
    protected abstract fun claim(host: PsiLanguageInjectionHost): HostSqlClaim

    /** Text before the SQL, such as a dialect comment. */
    protected open val prefix: String get() = ""

    private val claimKey = Key.create<Pair<Long, HostSqlClaim>>("${channel.id}.host.sql.claim")

    private fun claimOf(host: PsiLanguageInjectionHost): HostSqlClaim {
        val revision = host.containingFile?.modificationStamp ?: return claim(host)
        host.getUserData(claimKey)?.takeIf { it.first == revision }?.let { return it.second }
        return claim(host).also { host.putUserData(claimKey, revision to it) }
    }

    final override fun getInjection(context: PsiElement): Injection? {
        val host = context as? PsiLanguageInjectionHost ?: return null
        if (!host.isValidHost) return null
        val claim = claimOf(host)
        if (claim == HostSqlClaim.NotClaimed) return null
        val injected = (claim as? HostSqlClaim.Injected)?.language ?: language
        return SimpleInjection(injected, prefix, "", null)
    }

    final override fun isPrimary(): Boolean = true

    final override fun performInjection(registrar: MultiHostRegistrar, injection: Injection, context: PsiElement): Boolean {
        val host = context as? PsiLanguageInjectionHost ?: return false
        // Consumed once: the next injection pass asks the recognizer again.
        val claim = claimOf(host).also { host.putUserData(claimKey, null) }
        return channel.perform(registrar, host, claim, prefix)
    }
}
