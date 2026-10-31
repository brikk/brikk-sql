package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.database.Dbms
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.DatafusionCatalogProfile
import dev.brikk.house.sql.intellij.dialect.*
import dev.brikk.house.sql.intellij.parser.DatafusionFamilyParserDefinition
import dev.brikk.house.sql.intellij.pipe.DatafusionPipeDialect
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.verify.VerifyResult
import java.lang.reflect.Proxy

class DatafusionNativeErrorsTest : BasePlatformTestCase() {
    private val languages get() = listOf(DatafusionFixtureDialect, SpiceFixtureDialect)
    override fun setUp() {
        super.setUp()
        for (language in languages) {
            LanguageParserDefinitions.INSTANCE.addExplicitExtension(language, DatafusionFamilyParserDefinition(language), testRootDisposable)
            SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(language, object : SqlSyntaxHighlighterFactory.Base(language) {}, testRootDisposable)
        }
    }
    private fun messages(annotator: NativeErrorAnnotator, outcome: NativeErrorAnnotator.Outcome?): List<String> {
        val messages = mutableListOf<String>()
        lateinit var builder: AnnotationBuilder
        builder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnnotationBuilder::class.java)) { _, method, _ -> if (method.name == "create") Unit else builder } as AnnotationBuilder
        val holder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnnotationHolder::class.java)) { _, method, args ->
            if (method.name == "newAnnotation") messages += args[1] as String
            builder
        } as AnnotationHolder
        annotator.apply(myFixture.file, outcome, holder)
        return messages
    }
    fun testOfflineErrorsRemainAndConnectedValidationNeverPlansEditorSql() {
        for (language in languages) {
            myFixture.configureFromExistingVirtualFile(LightVirtualFile("errors.sql", language, "SELECT '😀'; SELECT * FROM t WHERE = 1"))
            val profile = DatafusionNativeErrorProfile(language, DatafusionCatalogProfile(Dbms.POSTGRES, language.profile.coreDialect))
            val annotator = object : NativeErrorAnnotator(profile) {}
            val offline = annotator.doAnnotate(annotator.collectInformation(myFixture.file))
            assertTrue(messages(annotator, offline).any { it.contains("brikk-sql") })
            RecordingConsole(project, myFixture.file.virtualFile, "postgresql").use { recording ->
                val validator = profile.validator(myFixture.file)!!
                for (sql in listOf("SELECT ai_decide('x','{}')", "SELECT * FROM text_search(docs,'q')", "EXPLAIN ANALYZE SELECT 1"))
                    assertTrue(validator.verify(sql, myFixture.file) is VerifyResult.Unavailable)
                assertEmpty(recording.requests)
                val outcome = annotator.doAnnotate(annotator.collectInformation(myFixture.file))
                assertTrue(messages(annotator, outcome).any { it.contains("brikk-sql") })
                recording.console.dataSource.username = "different"
                assertEmpty(messages(annotator, outcome))
            }
        }
    }
    fun testStaleTextAndCancellationDoNotApplyOldDiagnostics() {
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("stale.sql", SpiceFixtureDialect, "SELECT * FROM t WHERE = 1"))
        val profile = DatafusionNativeErrorProfile(SpiceFixtureDialect, DatafusionCatalogProfile(Dbms.POSTGRES, "spice"))
        val annotator = object : NativeErrorAnnotator(profile) {}
        val snapshot = annotator.collectInformation(myFixture.file)
        val outcome = annotator.doAnnotate(snapshot)
        assertTrue(messages(annotator, outcome).isNotEmpty())
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("SELECT 1") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEmpty(messages(annotator, outcome))
        val indicator = EmptyProgressIndicator()
        val failure = runCatching { ProgressManager.getInstance().runProcess({ indicator.cancel(); annotator.doAnnotate(annotator.collectInformation(myFixture.file)) }, indicator) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is ProcessCanceledException)
    }
    fun testNativeAndBrikkPipeModesHaveSeparateDiagnosticPolicies() {
        myFixture.configureFromExistingVirtualFile(LightVirtualFile("pipes.sql", DatafusionFixtureDialect, "FROM sample |> DROP id"))
        val catalog = DatafusionCatalogProfile(Dbms.POSTGRES)
        var enabled = false
        val pipe = DatafusionPipeDialect(DatafusionFixtureDialect, catalog) { enabled }
        val profile = DatafusionNativeErrorProfile(DatafusionFixtureDialect, catalog, pipe)
        val statement = pipe.scriptRanges(myFixture.file.text).single()
        assertNull(profile.statementDiagnostics(myFixture.file, myFixture.file.text, statement))
        val stamp = profile.modificationCount(myFixture.file)
        enabled = true
        assertTrue(profile.statementDiagnostics(myFixture.file, myFixture.file.text, statement)!!.single().message.contains("no qualified"))
        assertFalse(stamp == profile.modificationCount(myFixture.file))
        assertTrue(runCatching { DatafusionNativeErrorProfile(SpiceFixtureDialect, catalog) }.exceptionOrNull() is IllegalArgumentException)
    }
}
