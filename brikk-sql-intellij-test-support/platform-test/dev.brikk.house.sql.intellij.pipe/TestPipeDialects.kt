package dev.brikk.house.sql.intellij.pipe

import com.intellij.database.Dbms
import com.intellij.database.console.session.DatabaseSession
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.sql.psi.SqlLanguage

/** Profiles over each core PIPE dialect, with that plugin's first-stage policy, executing through H2 consoles. */
class TestPipeDialect(override val dialect: String, override val firstStage: FirstStageRule) : PipeDialect {
    override val label = "Test PIPE"
    override val vetoProperty = "brikk.test.pipes"
    override val language: Language = SqlLanguage.INSTANCE
    var enabled = true
    override fun isProjectEnabled(project: Project) = enabled
    override fun ownsSession(session: DatabaseSession) = session.connectionPoint?.dbms == Dbms.H2
}

val testPipeDialects = listOf(
    TestPipeDialect("trino", FirstStageRule.AnyQuery),
    TestPipeDialect("duckdb", FirstStageRule.AnyQuery),
    TestPipeDialect("doris", FirstStageRule.FromOnly),
)

const val TEST_DRIVER = "h2.unified"
