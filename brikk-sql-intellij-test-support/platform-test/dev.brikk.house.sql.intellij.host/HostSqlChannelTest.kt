package dev.brikk.house.sql.intellij.host

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.actionSystem.AnAction
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.pipe.FirstStageRule
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor
import dev.brikk.house.sql.intellij.pipe.TestPipeDialect
import dev.brikk.house.sql.intellij.relation.FakeFacts
import dev.brikk.house.sql.intellij.relation.RelationScopeProfile
import dev.brikk.house.sql.intellij.relation.relationDiagnostics

/** Claims performed: injection with exact host ranges, pending placeholders, unavailable strings, retries and the inspection-only check. */
class HostSqlChannelTest : HostSqlTestCase() {
    private val reanalyzed = mutableListOf<PsiFile>()

    override val channel: HostSqlChannel by lazy {
        object : HostSqlChannel("test") {
            override fun reanalyze(file: PsiFile) {
                reanalyzed += file
            }
        }
    }

    /** Marks every reference named `bad` in injected PostgreSQL. */
    private class BadNames : Annotator {
        override fun annotate(element: PsiElement, holder: AnnotationHolder) {
            if (element is SqlReferenceExpression && element.name == "bad") holder.newAnnotation(HighlightSeverity.ERROR, "bad name").range(element.textRange).create()
        }
    }

    private fun marked(severity: HighlightSeverity = HighlightSeverity.ERROR): List<String> =
        myFixture.doHighlighting(severity).map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }

    fun testProblemsInTheSqlAreMarkedAtTheirHostText() {
        LanguageAnnotators.INSTANCE.addExplicitExtension(postgres, BadNames(), testRootDisposable)
        // Escapes, a supplementary character and a trimmed indent before the names; one is a replacement.
        val text = "SELECT '\n    SELECT ''😀'', bad,\n      \\tbad\\r\\n    FROM t WHERE x = \${b}'"
        claim = { host ->
            val at = host.text.indexOf("\${b}")
            HostSqlClaim.Injected(postgres, HostSqlEntries(listOf(
                HostSqlEntry(TextRange(1, at)), HostSqlEntry(TextRange(at, at + 4), "bad"), HostSqlEntry(TextRange(at + 4, host.textLength - 1)),
            ), listOf(HostSqlTrim.Indent)))
        }
        open(text)
        assertEquals("SELECT '😀', bad,\n  \tbad\nFROM t WHERE x = bad", injected()?.let { InjectedLanguageManager.getInstance(project).getUnescapedText(it) })
        val marks = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description == "bad name" }.map { TextRange(it.startOffset, it.endOffset) }
        val first = text.indexOf("bad")
        val second = text.indexOf("bad", first + 1)
        assertSameElements(marks.filterNot { it.isEmpty }, TextRange(first, first + 3), TextRange(second, second + 3))
    }

    fun testPlaceholdersOfPendingSqlAreNoErrorsAndRelationDiagnosticsWait() {
        HighlightErrorFilter.EP_NAME.getPoint(project).registerExtension(object : HostSqlPlaceholderErrorFilter(channel) {}, testRootDisposable)
        var pending = false
        claim = { host ->
            val at = host.text.indexOf("\${x}")
            HostSqlClaim.Injected(postgres, HostSqlEntries(listOf(
                HostSqlEntry(TextRange(1, at)), HostSqlEntry(TextRange(at, at + 4), ")(", placeholder = pending), HostSqlEntry(TextRange(at + 4, host.textLength - 1)),
            )), pending = pending)
        }
        open("SELECT 'SELECT e.nope, \${x} FROM events() e'")
        assertNotEmpty(marked())
        val profile = object : RelationScopeProfile {
            override val language = postgres
            override val dialect = "postgres"
            override val providers = listOf(FakeFacts())
            override val hostSql = channel
        }
        pending = true
        open("SELECT 'SELECT e.nope, \${x} FROM events() e'")
        assertEmpty(marked())
        // Other errors stay.
        open("SELECT 'SELECT ) = 1; SELECT e.nope, \${x} FROM events() e'")
        assertNotEmpty(marked())
        assertTrue(channel.isPending(injected()))
        // A placeholder that parses: the missing column would be reported but for the pending state.
        claim = { host ->
            val at = host.text.indexOf("\${x}")
            HostSqlClaim.Injected(postgres, HostSqlEntries(listOf(
                HostSqlEntry(TextRange(1, at)), HostSqlEntry(TextRange(at, at + 4), "1", placeholder = true), HostSqlEntry(TextRange(at + 4, host.textLength - 1)),
            )), pending = true)
        }
        open("SELECT 'SELECT e.nope, \${x} FROM events() e'")
        assertEmpty(relationDiagnostics(checkNotNull(injected()), profile))
        claim = { host -> HostSqlClaim.Injected(postgres, content(host)) }
        open("SELECT 'SELECT e.nope FROM events() e'")
        assertEquals(listOf("nope"), relationDiagnostics(checkNotNull(injected()), profile).map { d -> injected()!!.let { InjectedLanguageManager.getInstance(project).getUnescapedText(it) }.substring(d.start, d.end) })
    }

    fun testAnUnavailableStringIsClaimedWithoutSqlAndRetriedOncePerModification() {
        claim = { HostSqlClaim.Unavailable("indexing") }
        open("SELECT 'SELECT 1'")
        assertNull(injected())
        assertEquals(HostSqlClaim.Unavailable("indexing"), channel.state(host())?.claim)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf(myFixture.file), reanalyzed)
        channel.scheduleRetry(host())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, reanalyzed.size)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        channel.scheduleRetry(host())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(2, reanalyzed.size)
    }

    fun testAProviderCanRequestAReanalysisWithoutAPsiChange() {
        claim = { host -> HostSqlClaim.Injected(postgres, content(host)) }
        open("SELECT 'SELECT 1'")
        channel.requestReanalysis(checkNotNull(injected()))
        channel.requestReanalysis(myFixture.file)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf(myFixture.file, myFixture.file), reanalyzed)
    }

    fun testARetryWaitsForIndexing() {
        open("SELECT 'SELECT 1'")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            channel.scheduleRetry(host())
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEmpty(reanalyzed)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, reanalyzed.size)
    }

    fun testTheMarkerSaysWhatTheSqlIs() {
        var inspectionOnly = true
        claim = { host -> HostSqlClaim.Injected(postgres, content(host), inspectionOnly = inspectionOnly, colonBinds = inspectionOnly) }
        open("SELECT 'SELECT :id', 'SELECT 2'")
        val file = checkNotNull(injected())
        assertTrue(channel.isInspectionOnly(file))
        assertTrue(channel.parsesColonBinds(file))
        assertFalse(channel.isPending(file))
        assertFalse("another channel's keys are its own", HostSqlChannel("other").isInspectionOnly(file))
        assertFalse(channel.isInspectionOnly(myFixture.file))
        inspectionOnly = false
        open("SELECT 'SELECT 1'")
        assertFalse(channel.isInspectionOnly(injected()))
        claim = { HostSqlClaim.NotClaimed }
        open("SELECT 'SELECT 1'")
        assertNull(injected())
        assertNull(channel.state(host()))
    }

    fun testPipeExecuteRefusesInspectionOnlySql() {
        var inspectionOnly = true
        claim = { host -> HostSqlClaim.Injected(postgres, content(host), inspectionOnly = inspectionOnly) }
        val dialect = TestPipeDialect("trino", FirstStageRule.AnyQuery, channel)
        /** Whether Execute fell through to the stock action. */
        fun execute(): Boolean {
            open("SELECT 'FROM t |> WHERE id > 0'")
            val file = checkNotNull(injected())
            val event = AnActionEvent.createEvent(
                SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, file).build(),
                Presentation(), "HostSqlChannelTest", ActionUiKind.NONE, null,
            )
            assertEquals(inspectionOnly, dialect.isInspectionHost(file))
            assertTrue(dialect.enablesEditing(file))
            var delegated = false
            val stock = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    delegated = true
                }
            }
            CooperativeExecuteAction(1, stock, PipeExecuteInterceptor(dialect)).actionPerformed(event)
            return delegated
        }
        assertFalse("refused", execute())
        inspectionOnly = false
        assertTrue("not claimed without a console", execute())
    }
}
