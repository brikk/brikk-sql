package dev.brikk.house.sql.intellij.host

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.codeInsight.daemon.impl.LineMarkersPass
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.ExternalLanguageAnnotators
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.lang.injection.general.LanguageInjectionPerformer
import com.intellij.lang.injection.general.SimpleInjection
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.SyntaxTraverser
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import dev.brikk.house.sql.intellij.completion.SqlLookupIcons
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator
import dev.brikk.house.sql.intellij.diagnostics.TestProfile
import dev.brikk.house.sql.intellij.pipe.FirstStageRule
import dev.brikk.house.sql.intellij.pipe.TestPipeDialect

/** Claims against other injectors, real reanalysis, placeholders in every report, and the execution guard from the host editor. */
class HostSqlClaimsTest : HostSqlTestCase() {
    private var reanalyses = 0
    private var claimed: (PsiLanguageInjectionHost) -> Boolean = { false }

    override val channel: HostSqlChannel by lazy {
        object : HostSqlChannel("claims") {
            override fun reanalyze(file: PsiFile) {
                reanalyses++
                check(reanalyses < 40) { "Reanalysis loop" }
                super.reanalyze(file)
            }

            override fun claimsHost(host: PsiLanguageInjectionHost) = claimed(host)
        }
    }

    /** Another contributor and performer of the host language, as an `@Language` annotation or a configured injection has. */
    private fun competingContributor() {
        LanguageInjectionContributor.INJECTOR_EXTENSION.addExplicitExtension(mysql, object : LanguageInjectionContributor {
            override fun getInjection(context: PsiElement): Injection = SimpleInjection(postgres, "", "", null)
        }, testRootDisposable)
        LanguageInjectionPerformer.INJECTOR_EXTENSION.addExplicitExtension(mysql, object : LanguageInjectionPerformer {
            override fun isPrimary() = false

            override fun performInjection(registrar: MultiHostRegistrar, injection: Injection, context: PsiElement): Boolean {
                val host = context as? PsiLanguageInjectionHost ?: return false
                registrar.startInjecting(injection.injectedLanguage ?: return false).addPlace("", "", host, TextRange(1, host.textLength - 1)).doneInjecting()
                return true
            }
        }, testRootDisposable)
    }

    /** Another injector outside the contributor mechanism. */
    private fun competingInjector() {
        MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME.getPoint(project).registerExtension(object : MultiHostInjector {
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                val host = context as? PsiLanguageInjectionHost ?: return
                registrar.startInjecting(postgres).addPlace("", "", host, TextRange(1, host.textLength - 1)).doneInjecting()
            }

            override fun elementsToInjectIn() = listOf(SqlStringLiteralExpression::class.java)
        }, testRootDisposable)
    }

    fun testAClaimedStringGetsNoOtherContributorsSqlIndexingOrNot() {
        competingContributor()
        claim = { HostSqlClaim.NotClaimed }
        open("SELECT 'SELECT 1'")
        assertNull("another contributor's SQL", channel.marker(checkNotNull(injected())))
        claim = { HostSqlClaim.Unavailable("indexing") }
        open("SELECT 'SELECT 1'")
        assertNull(injected())
        claim = { host -> HostSqlClaim.Injected(postgres, content(host)) }
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            open("SELECT 'SELECT 1'")
            assertNotNull(channel.marker(injected()))
        }
    }

    fun testSqlAnotherInjectorPutsIntoAClaimedStringIsInspectionOnly() {
        competingInjector()
        // The other injector comes first here, so the channel never decides; the recognizer's guard does.
        claim = { HostSqlClaim.Unavailable("indexing") }
        claimed = { host -> "helper" in host.text }
        open("SELECT 'SELECT helper', 'SELECT other'")
        assertTrue(channel.isInspectionOnly(checkNotNull(injected(host(0)))))
        assertFalse(channel.isInspectionOnly(checkNotNull(injected(host(1)))))
    }

    fun testPendingSqlIsInspectionOnly() {
        claim = { host -> HostSqlClaim.Injected(postgres, content(host), pending = true, inspectionOnly = false) }
        open("SELECT 'FROM t |> SELECT *'")
        assertTrue(channel.isInspectionOnly(injected()))
    }
}
