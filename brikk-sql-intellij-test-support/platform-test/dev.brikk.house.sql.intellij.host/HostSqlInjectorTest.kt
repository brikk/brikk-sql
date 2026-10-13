package dev.brikk.house.sql.intellij.host

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.lang.injection.general.LanguageInjectionPerformer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.configureDialect

/** The injector's claims with separate contributor and performer instances, as two plugin.xml declarations create them. */
class HostSqlInjectorTest : BasePlatformTestCase() {
    private val channel = HostSqlChannel("injector")
    private var recognized = false
    private var asked = 0
    private lateinit var contributor: HostSqlInjector

    private val postgres: Language get() = checkNotNull(Language.findLanguageByID("PostgreSQL"))

    private fun instance() = object : HostSqlInjector(channel, postgres) {
        override fun claim(host: PsiLanguageInjectionHost): HostSqlClaim {
            asked++
            return if (recognized) HostSqlClaim.Injected(postgres, HostSqlEntries(listOf(HostSqlEntry(TextRange(1, host.textLength - 1)))))
            else HostSqlClaim.NotClaimed
        }
    }

    override fun setUp() {
        super.setUp()
        val mysql = checkNotNull(Language.findLanguageByID("MySQL"))
        contributor = instance()
        LanguageInjectionContributor.INJECTOR_EXTENSION.addExplicitExtension(mysql, contributor, testRootDisposable)
        LanguageInjectionPerformer.INJECTOR_EXTENSION.addExplicitExtension(mysql, instance(), testRootDisposable)
    }

    private val host: SqlStringLiteralExpression get() = checkNotNull(PsiTreeUtil.findChildOfType(myFixture.file, SqlStringLiteralExpression::class.java))

    private fun injected() = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)

    private fun reanalyze() {
        channel.requestReanalysis(myFixture.file)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun testTheContributorAndPerformerShareOneRecognition() {
        recognized = true
        myFixture.configureDialect("MySQL", "SELECT 'SELECT 1'")
        assertNotNull(injected())
        assertEquals(1, asked)
    }

    fun testAStringRecognizedLaterIsInjectedOnReanalysis() {
        myFixture.configureDialect("MySQL", "SELECT 'SELECT 1'")
        assertNull(injected())
        recognized = true
        reanalyze()
        assertNotNull(injected())
    }

    fun testAWithdrawnRecognitionIsWithdrawnOnReanalysis() {
        recognized = true
        myFixture.configureDialect("MySQL", "SELECT 'SELECT 1'")
        assertNotNull(injected())
        recognized = false
        reanalyze()
        assertNull(contributor.getInjection(host))
        assertNull(injected())
    }

    fun testAClaimFollowsAChangeInAnotherFile() {
        // The recognizer reads a constant from another file, as a Kotlin const val interpolated into the string.
        val constants = myFixture.addFileToProject("Constants.txt", "old_column")
        recognized = true
        val withConstant = object : HostSqlInjector(HostSqlChannel("constants"), postgres) {
            override fun claim(host: PsiLanguageInjectionHost) = HostSqlClaim.Injected(postgres, HostSqlEntries(listOf(
                HostSqlEntry(TextRange(1, host.textLength - 1), "SELECT " + constants.text),
            )))
        }
        val mysql = checkNotNull(Language.findLanguageByID("MySQL"))
        LanguageInjectionContributor.INJECTOR_EXTENSION.addExplicitExtension(mysql, withConstant, testRootDisposable)
        LanguageInjectionPerformer.INJECTOR_EXTENSION.addExplicitExtension(mysql, withConstant, testRootDisposable)
        recognized = false
        myFixture.configureDialect("MySQL", "SELECT 'SELECT \${COLS}'")
        val manager = InjectedLanguageManager.getInstance(project)
        assertEquals("SELECT old_column", manager.getUnescapedText(checkNotNull(injected()).first().first.containingFile))
        WriteCommandAction.runWriteCommandAction(project) {
            val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(constants))
            document.setText("new_column")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertEquals("SELECT new_column", manager.getUnescapedText(checkNotNull(injected()).first().first.containingFile))
    }
}
