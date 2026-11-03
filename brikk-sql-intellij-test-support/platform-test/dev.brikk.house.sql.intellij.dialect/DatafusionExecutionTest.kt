package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.Dbms
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Disposer
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.DatafusionCatalogProfile
import dev.brikk.house.sql.intellij.parser.DatafusionFamilyParserDefinition
import dev.brikk.house.sql.intellij.pipe.*
import dev.brikk.house.sql.intellij.testing.PipeExecutionContract
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.settings.DatabaseSettings
import dev.brikk.house.sql.verify.VerifyResult
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import java.util.UUID

/** Consumer languages through stock console preparation. Transport has separate real Flight JDBC probes. */
class DatafusionExecutionTest : BasePlatformTestCase() {
    private val languages get() = listOf(DatafusionFixtureDialect, SpiceFixtureDialect)
    override fun setUp() {
        super.setUp()
        for (language in languages) {
            (language.editorProfile as DatafusionTestEditorProfile).version = null
            LanguageParserDefinitions.INSTANCE.addExplicitExtension(language, DatafusionFamilyParserDefinition(language), testRootDisposable)
            SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(language, object : SqlSyntaxHighlighterFactory.Base(language) {}, testRootDisposable)
        }
    }
    private fun each(check: PipeExecutionContract.() -> Unit) {
        for (language in languages) {
            var enabled = true
            // The recording console owns no backend. PostgreSQL's installed driver supplies its stock console machinery.
            val dialect = DatafusionPipeDialect(language, DatafusionCatalogProfile(Dbms.POSTGRES, language.profile.coreDialect)) { enabled }
            val contract = PipeExecutionContract(dialect, "postgresql", "h2.unified", { enabled = it }) { sql ->
                myFixture.configureFromExistingVirtualFile(LightVirtualFile("execute-${UUID.randomUUID()}.sql", language, sql))
                myFixture.editor
            }
            try { contract.check() } catch (failure: AssertionError) { throw AssertionError("${language.id}: ${failure.message}", failure) }
        }
    }
    fun testClaimsOnlyBrikkModePipes() = each { claimsOnlyPipes() }
    fun testNativeModeAndForeignSessionsDelegate() = each { declinesWhenNotOwned() }
    fun testSelectionAndMixedScriptsUseStockRules() = each { selectsStatementsLikeStockExecute() }
    fun testStaleDocumentDuringSaveAndParameterPromptingIsRefused() = each { refusesStaleInput() }
    fun testPromptingPinsDestinationAndSession() = each { refusesDestinationChangesDuringPrompting() }
    fun testPromptingPinsNamespace() = each { refusesNamespaceChangesDuringPrompting() }
    fun testPromptingPinsConnectionSettings() = each { refusesConnectionSettingsChangesDuringPrompting() }
    fun testCancellationPropagatesWithoutSubmission() = each { propagatesCancellation() }
    fun testRunToStageAndNestedPipelines() = each { runsTopLevelStagesToTheCaret(); lowersNestedOnlyPipelines() }
    fun testARefusedStatementBlocksTheWholeScript() = each { refusesWholeScriptsItCannotRun(); notifiesRefusals() }
    fun testUnknownStagesAndKnownMismatchedVersionsAreBlocked() {
        for (language in languages) {
            val dialect = DatafusionPipeDialect(language, DatafusionCatalogProfile(Dbms.POSTGRES, language.profile.coreDialect)) { true }
            assertTrue(dialect.lower("FROM sample |> DROP id") is PipeTranslation.Refused)
            assertTrue(dialect.lower("SELECT * FROM (FROM sample |> DROP id) s") is PipeTranslation.Refused)
            assertTrue(dialect.validator.verify("SELECT ai_decide('x','{}')", null) is VerifyResult.Unavailable)
            assertTrue(dialect.previewNote.contains("not native pipe syntax"))
            myFixture.configureFromExistingVirtualFile(LightVirtualFile("version.sql", language, "FROM sample |> SELECT id"))
            (language.editorProfile as DatafusionTestEditorProfile).version = "99.0.0"
            try { assertTrue(dialect.lower(myFixture.file.text, file = myFixture.file) is PipeTranslation.Refused) }
            finally { (language.editorProfile as DatafusionTestEditorProfile).version = null }
            val profile = language.editorProfile as DatafusionTestEditorProfile
            profile.parserSettings = mapOf("datafusion.sql_parser.dialect" to "mysql")
            try { assertTrue(dialect.lower(myFixture.file.text, file = myFixture.file) is PipeTranslation.Refused) }
            finally { profile.parserSettings = emptyMap() }
        }
    }
    fun testPipelineInputColumnsUseTheCopiedRelationFacts() {
        val settings = CodeInsightSettings.getInstance()
        val old = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        Disposer.register(testRootDisposable) { settings.AUTOCOMPLETE_ON_CODE_COMPLETION = old }
        for (language in languages) {
            val profile = language.editorProfile as DatafusionTestEditorProfile
            profile.fake.facts = RelationFacts(tables = mapOf("sample" to RelationFact(listOf(ColumnFact("id", "Int64"), ColumnFact("body", "Utf8")))))
            profile.fake.revision++
            profile.fake.pending = false
            val descriptor = DefaultPluginDescriptor(PluginId.getId("brikk.datafusion.execution.test"), javaClass.classLoader)
            val type = if (language === SpiceFixtureDialect) SpColumns::class.java else DfColumns::class.java
            CompletionContributor.EP.point.registerExtension(CompletionContributorEP(language.id, type.name, descriptor), LoadingOrder.FIRST, testRootDisposable)
            val sql = "FROM sample |> WHERE "
            myFixture.configureFromExistingVirtualFile(LightVirtualFile("input-${UUID.randomUUID()}.sql", language, sql))
            myFixture.editor.caretModel.moveToOffset(sql.length)
            val names = myFixture.completeBasic().orEmpty().map { it.lookupString }
            assertTrue(names.toString(), names.containsAll(listOf("id", "body")))
        }
    }
    fun testExplicitExplainUsesTheConsoleAndStockParameterStorage() {
        for (language in languages) {
            myFixture.configureFromExistingVirtualFile(LightVirtualFile("explain-${UUID.randomUUID()}.sql", language,
                "EXPLAIN SELECT :n; EXPLAIN ANALYZE SELECT :n"))
            RecordingConsole(project, myFixture.file.virtualFile, "postgresql").use { recording ->
                ShowSqlParametersPanelAction.getStorage(recording.console).putValue("n", "3")
                recording.console.executeQueries(myFixture.editor, recording.console.scriptModel, DatabaseSettings.ExecOption())
                (recording.requests.last() as ConsoleDataRequest).onFinished()
                assertEquals(listOf("EXPLAIN SELECT 3", "EXPLAIN ANALYZE SELECT 3"),
                    recording.requests.map { (it as DataRequest.QueryRequest).query.trim().removeSuffix(";") })
                assertTrue(recording.requests.all { it.owner === recording.console })
            }
        }
    }
    fun testSqlPrepareKeepsServerOwnedParametersOutOfClientPrompting() {
        for (language in languages) {
            val sql = "PREPARE brikk_named(BIGINT) AS SELECT $1 + 1"
            myFixture.configureFromExistingVirtualFile(LightVirtualFile("prepare-${UUID.randomUUID()}.sql", language, sql))
            RecordingConsole(project, myFixture.file.virtualFile, "postgresql").use { recording ->
                val model = recording.console.scriptModel
                assertTrue("${language.id}/${model.language.id}/${myFixture.file.language.id}: " + model.parameters().map { "${it.name()}:${it.type()}:${it.text()}:${it.`object`()}" }.toList().toString(), model.parameters().none { it.text() == "$1" })
                recording.console.executeQueries(myFixture.editor, model, DatabaseSettings.ExecOption())
                assertEquals(sql, (recording.requests.single() as DataRequest.QueryRequest).query.trim())
            }
        }
    }
}
