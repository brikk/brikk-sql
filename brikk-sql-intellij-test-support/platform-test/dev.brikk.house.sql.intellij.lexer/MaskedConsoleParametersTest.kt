package dev.brikk.house.sql.intellij.lexer

import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.settings.DatabaseSettings
import com.intellij.database.settings.UserPatterns
import dev.brikk.house.sql.intellij.fixture.FixtureTestCase
import dev.brikk.house.sql.intellij.testing.RecordingConsole

class MaskedConsoleParametersTest : FixtureTestCase() {
    fun testParametersInAMaskedStatementSubmitTheWholeStatement() {
        val settings = DatabaseSettings.getSettings()
        val saved = settings.userPatterns
        settings.userPatterns = UserPatterns().apply {
            inScripts = true; inLiterals = true; processStrings = true
            patterns = mutableListOf(UserPatterns.ParameterPattern("\\$\\{([^\\{\\}]*)\\}", "", "\${name}").enabled(true, true))
        }
        settings.patternCache.clear()
        try {
            configureFixture("SELECT * EXCLUDE (a) FROM t WHERE id = \${id} AND status = '\${status}' LIMIT \${rows}")
            RecordingConsole(project, myFixture.file.virtualFile, "h2.unified").use { recording ->
                val storage = ShowSqlParametersPanelAction.getStorage(recording.console)
                storage.putValue("id", "17"); storage.putValue("status", "open"); storage.putValue("rows", "3")
                recording.console.executeQueries(myFixture.editor, recording.console.scriptModel, DatabaseSettings.ExecOption())
                assertEquals("SELECT * EXCLUDE (a) FROM t WHERE id = 17 AND status = 'open' LIMIT 3",
                    (recording.requests.single() as DataRequest.QueryRequest).query.trim())
            }
        } finally {
            settings.userPatterns = saved
            settings.patternCache.clear()
        }
    }
}
