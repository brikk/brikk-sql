package dev.brikk.house.sql.intellij.pipe

import com.intellij.database.Dbms
import com.intellij.database.console.session.DatabaseSession
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.psi.SqlLanguage
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import java.util.UUID

/**
 * Profiles over each core PIPE dialect, with that plugin's first-stage policy, executing through
 * BigQuery consoles: the bundled BigQuery PSI splits PIPE statements as the plugins' own PSI does.
 */
class TestPipeDialect(override val dialect: String, override val firstStage: FirstStageRule) : PipeDialect {
    override val label = "Test PIPE"
    override val vetoProperty = "brikk.test.pipes"
    override val language: Language = SqlLanguage.INSTANCE
    var enabled = true
    override fun isProjectEnabled(project: Project) = enabled
    override fun ownsSession(session: DatabaseSession) = session.connectionPoint?.dbms == Dbms.BIGQUERY
}

val testPipeDialects = listOf(
    TestPipeDialect("trino", FirstStageRule.AnyQuery),
    TestPipeDialect("duckdb", FirstStageRule.AnyQuery),
    TestPipeDialect("doris", FirstStageRule.FromOnly),
)

const val TEST_DRIVER = "bigquery"
const val FOREIGN_DRIVER = "h2.unified"

/** Opens [sql] as a BigQuery-mapped file until [disposable] ends. */
fun CodeInsightTestFixture.openPipeSql(sql: String, disposable: Disposable): Editor {
    val file = configureByText("pipe-${UUID.randomUUID()}.sql", sql).virtualFile
    val mappings = SqlDialectMappings.getInstance(project)
    mappings.setMapping(file, Language.findLanguageByID("BigQuery") as SqlLanguageDialect)
    Disposer.register(disposable) { mappings.setMapping(file, null) }
    return editor
}
