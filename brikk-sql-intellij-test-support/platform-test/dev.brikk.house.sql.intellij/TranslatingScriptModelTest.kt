package dev.brikk.house.sql.intellij

import com.intellij.database.datagrid.DataRequest
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.settings.DatabaseSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import java.util.UUID

class TranslatingScriptModelTest : BasePlatformTestCase() {
    private val adapter = object : SqlExecutionAdapter {
        override fun claims(text: String) = "'pipe'" in text
        override fun translate(text: String, beforeSubstitution: Boolean) = RenderedSql(text.replace("'pipe'", "'translated'"), text)
    }

    fun testWholeScriptModelWithoutTextRangeExecutes() {
        val file = myFixture.configureByText("whole-script-${UUID.randomUUID()}.sql", "SELECT 1;\nSELECT 'pipe';").virtualFile
        RecordingConsole(project, file, "h2.unified").use { recording ->
            val console = recording.console
            assertNull("A whole-script model has no text range", console.scriptModel.textRange)
            val model = TranslatingScriptModel(console.scriptModel, myFixture.editor, adapter)

            model.finalizeTranslations(console.pStorage)
            console.executeQueries(myFixture.editor, model, DatabaseSettings.ExecOption())
            (recording.requests.last() as ConsoleDataRequest).onFinished()

            assertEquals(listOf("SELECT 1", "SELECT 'translated'"),
                recording.requests.map { (it as DataRequest.QueryRequest).query.trim().removeSuffix(";") })
        }
    }
}
