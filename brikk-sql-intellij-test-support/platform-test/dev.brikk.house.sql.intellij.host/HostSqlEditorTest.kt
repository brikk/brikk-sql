package dev.brikk.house.sql.intellij.host

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.codeInsight.daemon.LineMarkerSettings
import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.EditorNotificationPanel
import dev.brikk.house.sql.intellij.completion.SqlLookupIcons

/** The per-string gutter icon and the action-needed banner. */
class HostSqlEditorTest : HostSqlTestCase() {
    // Retries are HostSqlChannelTest's; here they would restart the daemon while the test highlights.
    override val channel: HostSqlChannel by lazy {
        object : HostSqlChannel("test") {
            override fun reanalyze(file: PsiFile) = Unit
        }
    }

    private val markers by lazy {
        object : HostSqlLineMarkerProvider(channel, "Test SQL strings", SqlLookupIcons.BrikkColumn) {
            override fun tooltip(state: HostSqlState) = when (val claim = state.claim) {
                is HostSqlClaim.Injected -> if (claim.pending) "SQL, waiting for indexing" else "SQL: ${claim.data}"
                is HostSqlClaim.Unavailable -> "SQL: ${claim.reason}"
                HostSqlClaim.NotClaimed -> "none"
            }
        }
    }

    override fun setUp() {
        super.setUp()
        LineMarkerProviders.getInstance().addExplicitExtension(checkNotNull(Language.findLanguageByID("MySQL")), markers, testRootDisposable)
    }

    /** The test gutter icons, as tooltip and whether dimmed. */
    private fun gutters(): List<Pair<String?, Boolean>> =
        myFixture.findAllGutters().filter { it.tooltipText?.startsWith("SQL") == true }.map { it.tooltipText to (it.icon != SqlLookupIcons.BrikkColumn) }

    fun testEachClaimedStringHasAnIconDimmedWhilePending() {
        claim = { host ->
            when {
                "ready" in host.text -> HostSqlClaim.Injected(postgres, content(host), data = "PostgreSQL, inspection only")
                "pending" in host.text -> HostSqlClaim.Injected(postgres, content(host), pending = true)
                "later" in host.text -> HostSqlClaim.Unavailable("indexing")
                else -> HostSqlClaim.NotClaimed
            }
        }
        // Light tests derive no disabled icons unless the loader is active.
        IconLoader.activate()
        Disposer.register(testRootDisposable, IconLoader::deactivate)
        open("SELECT 'SELECT ready',\n'SELECT pending',\n'SELECT later',\n'SELECT other'")
        assertSameElements(gutters(), "SQL: PostgreSQL, inspection only" to false, "SQL, waiting for indexing" to true, "SQL: indexing" to true)
    }

    fun testTheIconCanBeTurnedOffInSettings() {
        claim = { host -> HostSqlClaim.Injected(postgres, content(host), data = "on") }
        open("SELECT 'SELECT 1'")
        assertEquals(1, gutters().size)
        val settings = LineMarkerSettings.getSettings()
        settings.setEnabled(markers, false)
        Disposer.register(testRootDisposable) { settings.setEnabled(markers, true) }
        DaemonCodeAnalyzer.getInstance(project).restart()
        assertEmpty(gutters())
    }

    fun testTheBannerShowsOnlyWhenActionIsNeeded() {
        var needed: HostSqlActionBanner.Action? = null
        var fixed = false
        val banner = object : HostSqlActionBanner() {
            override fun actionNeeded(project: Project, file: VirtualFile) = needed
        }
        open("SELECT 1")
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        assertNull(banner.collectNotificationData(project, myFixture.file.virtualFile))
        needed = HostSqlActionBanner.Action("The helper is too old: SQL strings are not edited.", "Update the helper") { fixed = true }
        val panel = checkNotNull(banner.collectNotificationData(project, myFixture.file.virtualFile)).apply(editor) as EditorNotificationPanel
        assertEquals("The helper is too old: SQL strings are not edited.", panel.text)
        assertFalse(fixed)
    }
}
