package dev.brikk.house.sql.intellij

import com.intellij.database.datagrid.DataRequest
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.settings.DatabaseSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import java.util.UUID
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange

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

    fun testAnAlreadyCapturedSourceMapRejectsLaterEdits() {
        val file = myFixture.configureByText("late-error.sql", "SELECT 'pipe'").virtualFile
        val mapped = object : SqlExecutionAdapter {
            override fun claims(text: String) = true
            override fun translate(text: String, beforeSubstitution: Boolean) = RenderedSql(text, text) { _, _ -> TextRange(0, 6) }
        }
        RecordingConsole(project, file, "h2.unified").use { recording ->
            val model = TranslatingScriptModel(recording.console.scriptModel, myFixture.editor, mapped)
            model.finalizeTranslations(recording.console.pStorage)
            val statement = model.statements().first()!!
            val callback = model.authoredMap(statement.range(), statement.rangeOffset())!!
            assertEquals(TextRange(0, 6), callback(1, 1))
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "-- edited\n") }
            assertNull(callback(1, 1))
        }
    }
}
