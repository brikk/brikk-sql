package dev.brikk.house.sql.intellij.lexer

import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.settings.DatabaseSettings
import com.intellij.lang.ParserDefinition
import com.intellij.sql.psi.SqlPsiFacade
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase
import dev.brikk.house.sql.intellij.parser.MaskingCHouseParserDefinition
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.assertRestartsMatchFullLex
import dev.brikk.house.sql.intellij.testing.lexAll

/** IDE `:name` binds inside a masked span stay parameters the console finds, prompts for and substitutes. */
class MaskedSpanParametersTest : CHouseFixtureTestCase() {
    private val definition = MaskingCHouseParserDefinition()

    override fun parserDefinition(): ParserDefinition = definition

    private val text = "INSERT INTO FUNCTION s3('x') PARTITION BY concat(:prefix, ':no', toString(a::Int8), b) -- :nor\nSELECT :q"

    private fun parameters(): List<String> =
        SqlPsiFacade.getInstance(project).createScriptModel(myFixture.file).parameters().map { it.name() }.toList()

    fun testParametersInAMaskedSpanAreFound() {
        configureCHouse(text)
        assertEquals(listOf("prefix", "q"), parameters())
    }

    fun testABindOnTheExportsOneLineIsFound() {
        configureCHouse("INSERT INTO FUNCTION null('id UInt64') PARTITION BY toString(:partition) SELECT 1 AS id;")
        assertEquals(listOf("partition"), parameters())
    }

    fun testTheConsoleSubstitutesThem() {
        configureCHouse(text)
        RecordingConsole(project, myFixture.file.virtualFile, "h2.unified").use { recording ->
            val storage = ShowSqlParametersPanelAction.getStorage(recording.console)
            storage.putValue("prefix", "'p'")
            storage.putValue("q", "7")
            recording.console.executeQueries(myFixture.editor, recording.console.scriptModel, DatabaseSettings.ExecOption())
            val query = (recording.requests.single() as DataRequest.QueryRequest).query
            assertTrue(query, query.contains("concat('p', ':no'"))
            assertTrue(query, query.endsWith("SELECT 7"))
        }
    }

    fun testStatesStayNonNegativeAndRestartable() {
        assertTrue(definition.createLexer(project).lexAll(text).all { it.state >= 0 })
        assertRestartsMatchFullLex({ definition.createLexer(project) }, "$text;\n$text")
    }
}
