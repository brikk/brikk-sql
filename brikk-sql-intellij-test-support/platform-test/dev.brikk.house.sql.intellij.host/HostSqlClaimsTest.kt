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
import dev.brikk.house.sql.intellij.testing.HostSqlExecutionContract

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

    fun testExecuteFromTheHostEditorIsRefused() {
        claim = { host -> HostSqlClaim.Injected(postgres, content(host)) }
        HostSqlExecutionContract(TestPipeDialect("trino", FirstStageRule.AnyQuery, channel)) {
            open("SELECT 'FROM t |> WHERE id > <caret>0'")
            myFixture.editor
        }.refusesInspectionOnlySqlFromTheHostEditor()
    }

    fun testARetryInjectsOnceTheFactsAreThereAndStops() {
        var available = false
        claim = { host -> if (available) HostSqlClaim.Injected(postgres, content(host)) else HostSqlClaim.Unavailable("pending") }
        open("SELECT 'SELECT 1'")
        assertNull(injected())
        available = true
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, reanalyses)
        assertNotNull(injected())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, reanalyses)
    }

    fun testAStillUnavailableStringDoesNotLoop() {
        claim = { HostSqlClaim.Unavailable("pending") }
        open("SELECT 'SELECT 1'")
        repeat(8) {
            assertNull(injected())
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        assertEquals(1, reanalyses)
    }

    fun testARequestedReanalysisReplacesTheSql() {
        var value = "1"
        claim = { host -> HostSqlClaim.Injected(postgres, HostSqlEntries(listOf(HostSqlEntry(TextRange(1, host.textLength - 1), "SELECT $value")))) }
        open("SELECT 'SELECT 0'")
        val manager = InjectedLanguageManager.getInstance(project)
        assertEquals("SELECT 1", manager.getUnescapedText(checkNotNull(injected())))
        value = "2"
        channel.requestReanalysis(myFixture.file)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("SELECT 2", manager.getUnescapedText(checkNotNull(injected())))
    }

    fun testHostsOfOneFileShareARetryAndEachRevisionGetsOne() {
        claim = { HostSqlClaim.Unavailable("pending") }
        open("SELECT 'SELECT 1', 'SELECT 2', 'SELECT 3'")
        (0..2).forEach { injected(host(it)) }
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            repeat(3) {
                WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                (0..2).forEach { channel.scheduleRetry(host(it)) }
            }
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("the first revision's and three edits'", 4, reanalyses)
    }

    fun testAnUnmatchedParenthesisAfterAPlaceholderStaysAnError() {
        HighlightErrorFilter.EP_NAME.getPoint(project).registerExtension(object : HostSqlPlaceholderErrorFilter(channel) {}, testRootDisposable)
        claim = { host ->
            val at = host.text.indexOf("\${x}")
            HostSqlClaim.Injected(postgres, HostSqlEntries(listOf(
                HostSqlEntry(TextRange(1, at)), HostSqlEntry(TextRange(at, at + 4), "1", placeholder = true), HostSqlEntry(TextRange(at + 4, host.textLength - 1)),
            )), pending = true)
        }
        open("SELECT 'SELECT \${x})'")
        assertNotEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    private fun nativeAnnotator(reportsPsiErrors: Boolean) {
        val profile = TestProfile(postgres, "postgres", "Placeholder verifier").apply {
            injectionHosts = setOf(mysql)
            this.reportsPsiErrors = reportsPsiErrors
            if (reportsPsiErrors) validator = null
            hostSql = channel
        }
        ExternalLanguageAnnotators.INSTANCE.addExplicitExtension(mysql, object : NativeErrorAnnotator(profile) {}, testRootDisposable)
        HighlightErrorFilter.EP_NAME.getPoint(project).registerExtension(object : HostSqlPlaceholderErrorFilter(channel) {}, testRootDisposable)
    }

    private fun placeholder(replacement: String, text: String = "SELECT 'SELECT \${x} FROM events() e'") {
        claim = { host ->
            val at = host.text.indexOf("\${x}")
            HostSqlClaim.Injected(postgres, HostSqlEntries(listOf(
                HostSqlEntry(TextRange(1, at)), HostSqlEntry(TextRange(at, at + 4), replacement, placeholder = true), HostSqlEntry(TextRange(at + 4, host.textLength - 1)),
            )), pending = true)
        }
        open(text)
    }

    private fun verifierReports() = myFixture.doHighlighting().filter { it.description?.startsWith("Placeholder verifier") == true }.map { it.description }

    fun testNativeVerificationSkipsStatementsWithPlaceholders() {
        nativeAnnotator(reportsPsiErrors = false)
        // The verifier rejects `bad`: as a placeholder, it is not the SQL that runs.
        placeholder("bad")
        assertEmpty(verifierReports())
        placeholder("bad", "SELECT 'SELECT bad; SELECT \${x} FROM events() e'")
        assertEquals(1, verifierReports().size)
    }

    fun testParseErrorsAPlaceholderCausesDoNotComeBackThroughTheNativeAnnotator() {
        nativeAnnotator(reportsPsiErrors = true)
        placeholder(")(")
        assertEmpty(verifierReports())
    }
}
