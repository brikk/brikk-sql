package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.ObjectKind
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.CooperativeAction
import dev.brikk.house.sql.intellij.CooperativeActionConfiguration
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.metadata.TRINO_FUNCTION_CATALOG
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope

/** Explain Plan in a console of the profile's dbms shows text explain; another plugin's registration on the same action chains. */
class TextExplainActionTest : BasePlatformTestCase() {
    class Profile(override val dbms: Dbms, override val enabled: Boolean = true) : TextExplainProfile {
        override val productLabel = "Fixture"
        override val dialect = "trino"
        override val builtinFunctions: FunctionCatalog = TRINO_FUNCTION_CATALOG
        override val searchPath = CatalogSchemaSearchPath(
            '"', "USE {catalog}.{schema}",
            catalogProbes = listOf(NamespaceProbe.Value("SELECT current_database()")),
            schemaProbes = listOf(NamespaceProbe.Value("SELECT current_schema()")),
        )
    }

    /** Records what would be shown instead of a popup. */
    open class Service(project: Project, scope: CoroutineScope) : TextExplainService(project, scope) {
        override var profile: TextExplainProfile = Profile(Dbms.UNKNOWN)
        val shown = CopyOnWriteArrayList<ExplainOutcome>()

        override fun present(outcome: ExplainOutcome) {
            shown += outcome
        }
    }

    class Foreign(project: Project, scope: CoroutineScope) : Service(project, scope)

    class Ours : TextExplainConfiguration(Service::class.java)
    class Theirs : TextExplainConfiguration(Foreign::class.java)

    private lateinit var errors: BackgroundErrors
    private lateinit var dataSource: LocalDataSource
    private lateinit var recording: RecordingConsole
    private lateinit var ours: Service
    private lateinit var theirs: Foreign
    private val manager get() = ActionManager.getInstance()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        dataSource = realDuckdbDataSource(project, testRootDisposable)
        val catalog = withIdeConnection(project, dataSource, otherRequestor()) { connection ->
            val statement = checkNotNull(JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() })
            try {
                JdbcNativeUtil.computeRemote { statement.execute("CREATE SCHEMA web; CREATE TABLE web.t(x INT)") }
                val rows = checkNotNull(JdbcNativeUtil.computeRemote { statement.executeQuery("SELECT current_database()") })
                rows.next()
                checkNotNull(rows.getString(1)).also { JdbcNativeUtil.performSafe { rows.close() } }
            } finally {
                JdbcNativeUtil.closeRemoteStatementSafe(statement)
            }
        }
        myFixture.configureByText("explain-${UUID.randomUUID()}.sql", "SELECT 1;\nSELECT count(*) FROM t WHERE x > 1;\n")
        recording = RecordingConsole(project, myFixture.file.virtualFile, dataSource)
        recording.console.setSearchPath(SearchPath.of(ObjectPath.create(catalog, ObjectKind.DATABASE).append("web", ObjectKind.SCHEMA)))
        ours = Service(project, errors.newScope(testRootDisposable)).apply { profile = Profile(dataSource.dbms) }
        theirs = Foreign(project, errors.newScope(testRootDisposable)).apply { profile = Profile(Dbms.MYSQL) }
        project.registerOrReplaceServiceInstance(Service::class.java, ours, testRootDisposable)
        project.registerOrReplaceServiceInstance(Foreign::class.java, theirs, testRootDisposable)
    }

    override fun tearDown() {
        try {
            recording.close()
            errors.awaitScopes()
            errors.assertNone()
        } finally {
            super.tearDown()
        }
    }

    private fun event(action: AnAction): AnActionEvent = TestActionEvent.createTestEvent(
        action,
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
            .build(),
    )

    private fun <T> installed(block: (AnAction) -> T): T {
        val configurations = listOf(Theirs(), Ours())
        configurations.forEach { it.registerActions(manager) }
        try {
            return block(checkNotNull(manager.getAction(CooperativeActionConfiguration.EXPLAIN_PLAN)))
        } finally {
            configurations.asReversed().forEach { it.unregisterActions(manager) }
        }
    }

    fun testExplainPlanInAnOwnedConsoleExplainsTheStatementAtTheCaret() {
        installed { action ->
            val outer = action as CooperativeAction
            assertTrue("another plugin's wrapper is chained, not replaced", outer.delegate is CooperativeAction)
            myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("count"))
            val event = event(action)
            action.update(event)
            assertTrue(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
            PlatformTestUtil.waitWithEventsDispatching("the plan is shown", { ours.shown.isNotEmpty() }, 30)
            assertEquals(listOf<ExplainOutcome>(ExplainOutcome.Plan("physical_plan")), ours.shown.toList())
            assertEmpty("the other plugin's console check declined", theirs.shown)
            assertEmpty("the console's session is never used", recording.requests)
        }
    }

    fun testASelectionIsExplainedAsSelected() {
        installed { action ->
            val text = myFixture.file.text
            myFixture.editor.selectionModel.setSelection(text.indexOf("SELECT 1"), text.indexOf(";"))
            action.actionPerformed(event(action))
            PlatformTestUtil.waitWithEventsDispatching("the plan is shown", { ours.shown.isNotEmpty() }, 30)
            assertEquals(listOf<ExplainOutcome>(ExplainOutcome.Plan("physical_plan")), ours.shown.toList())
        }
    }

    /** An owned console's Explain Plan is handled even with nothing to explain: it never falls through to the stock action. */
    fun testNothingToExplainIsSaidNotPassedOn() {
        installed { action ->
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("SELECT 1;\n\n   ") }
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
            action.actionPerformed(event(action))
            val noEditor = TestActionEvent.createTestEvent(
                action,
                SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, myFixture.file)
                    .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile).build(),
            )
            action.actionPerformed(noEditor)
            assertEquals(2, ours.shown.size)
            assertTrue(ours.shown.toString(), ours.shown.all { it is ExplainOutcome.Refused && "no statement" in it.reason })
            assertEmpty(recording.requests)
        }
    }

    /** Off, the console is not claimed: the stock update decides, exactly as without the wrapper. */
    fun testADisabledProfileLeavesExplainToTheStockAction() {
        ours.profile = Profile(dataSource.dbms, enabled = false)
        val stock = checkNotNull(manager.getAction(CooperativeActionConfiguration.EXPLAIN_PLAN))
        val expected = event(stock).also(stock::update).presentation.isEnabledAndVisible
        installed { action ->
            assertEquals(expected, event(action).also(action::update).presentation.isEnabledAndVisible)
        }
        assertEmpty(ours.shown)
    }
}
