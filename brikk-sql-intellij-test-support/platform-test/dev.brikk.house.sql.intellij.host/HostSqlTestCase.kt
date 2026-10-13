package dev.brikk.house.sql.intellij.host

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.configureDialect

/**
 * Host strings are MySQL string literals, whose escaper decodes backslash escapes and doubled
 * quotes; each literal's claim comes from [claim], performed by a test injector through [channel].
 */
abstract class HostSqlTestCase : BasePlatformTestCase() {
    protected open val channel: HostSqlChannel by lazy { HostSqlChannel("test") }

    protected var claim: (PsiLanguageInjectionHost) -> HostSqlClaim = { HostSqlClaim.NotClaimed }

    protected val postgres: Language get() = checkNotNull(Language.findLanguageByID("PostgreSQL"))

    /** The whole content of [host] between its quotes, kept. */
    protected fun content(host: PsiLanguageInjectionHost) = HostSqlEntries(listOf(HostSqlEntry(TextRange(1, host.textLength - 1))))

    /** Opens [text] as a MySQL file whose string literals [claim] decides. */
    protected fun open(text: String) {
        myFixture.configureDialect("MySQL", if ("<caret>" in text) text else "$text<caret>")
        val manager = InjectedLanguageManager.getInstance(project)
        manager.registerMultiHostInjector(object : MultiHostInjector {
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                if (manager.isInjectedFragment(context.containingFile)) return
                val host = context as? PsiLanguageInjectionHost ?: return
                channel.perform(registrar, host, claim(host))
            }

            override fun elementsToInjectIn() = listOf(SqlStringLiteralExpression::class.java)
        }, testRootDisposable)
        manager.dropFileCaches(myFixture.file)
    }

    /** The [nth] string literal of the open file. */
    protected fun host(nth: Int = 0): PsiLanguageInjectionHost =
        PsiTreeUtil.findChildrenOfType(myFixture.file, SqlStringLiteralExpression::class.java).elementAt(nth) as PsiLanguageInjectionHost

    /** The SQL injected into [host], or null. */
    protected fun injected(host: PsiLanguageInjectionHost = host()): PsiFile? =
        InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)?.map { it.first.containingFile }?.distinct()?.single()
}
