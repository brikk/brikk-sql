package dev.brikk.house.sql.intellij.pipe

import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.datagrid.DataRequest
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.UiInterceptors
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.SqlExecutionRefusal
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.parser.ColumnUnit
import java.util.UUID

/** Support behaviors beyond the shared contract, on a Trino-dialect profile. */
class PipeExecutionTest : BasePlatformTestCase() {
    private val program = "FROM offline_rows |> WHERE id = :id |> SELECT id"

    fun testDisablingPipeDuringPromptingSubmitsNothing() {
        val dialect = TestPipeDialect("trino", FirstStageRule.AnyQuery)
        for (runToStage in listOf(false, true)) Run(dialect, program).use { run ->
            run.prompting({ dialect.enabled = false }) { if (runToStage) run.runToStage() else run.execute() }
            assertEmpty(run.recording.requests)
            assertTrue(run.notifications.any { it.type == NotificationType.ERROR })
            dialect.enabled = true
        }
    }

    fun testLoweringValidatorChecksMaskedThenSubstitutedSql() {
        val seen = mutableListOf<String>()
        val dialect = object : PipeDialect by TestPipeDialect("trino", FirstStageRule.AnyQuery) {
            override val validator = object : NativeSqlValidator {
                override val timing = ValidationTiming.Lowering
                override fun validate(sql: String, file: PsiFile?): NativeVerdict {
                    seen += sql
                    return if ("17" in sql) NativeVerdict.Rejected("no 17") else NativeVerdict.Accepted
                }
            }
        }
        assertEquals(NativeVerdict.Accepted, (dialect.lower(program) as PipeTranslation.Lowered).verdict)
        assertTrue(seen.single(), ":id" !in seen.single())
        Run(dialect, program).use { run ->
            run.prompting(value = "7") { run.execute() }
            assertEquals(1, run.recording.requests.size)
            assertTrue(seen.last(), "7" in seen.last())
        }
        Run(dialect, program).use { run ->
            run.prompting(value = "17") { run.execute() }
            assertEmpty(run.recording.requests)
            assertTrue(run.notifications.any { it.type == NotificationType.ERROR })
        }
    }

    fun testSubmissionValidatorChecksOnlyFinalSqlAndUnavailableProceeds() {
        var verdict: NativeVerdict = NativeVerdict.Unavailable
        val seen = mutableListOf<String>()
        val dialect = object : PipeDialect by TestPipeDialect("trino", FirstStageRule.AnyQuery) {
            override val validator = object : NativeSqlValidator {
                override val timing = ValidationTiming.Submission
                override fun validate(sql: String, file: PsiFile?): NativeVerdict = verdict.also { seen += sql }
            }
        }
        assertNull((dialect.lower(program) as PipeTranslation.Lowered).verdict)
        Run(dialect, program).use { run ->
            run.prompting(value = "7") { run.execute() }
            assertEquals(1, run.recording.requests.size)
            assertEquals(listOf(run.queries.single()), seen)
        }
        verdict = NativeVerdict.Rejected("rejected")
        Run(dialect, program).use { run ->
            run.prompting(value = "7") { run.execute() }
            assertEmpty(run.recording.requests)
            assertTrue(run.notifications.any { it.type == NotificationType.ERROR })
        }
    }

    fun testSubmitListenerSeesTranslatedStatementsAndTheirServerPositions() {
        for (firstColumn in listOf(0, 1)) {
            val runs = mutableListOf<String>()
            var observed: PipeRun? = null
            val dialect = object : PipeDialect by TestPipeDialect("trino", FirstStageRule.AnyQuery) {
                override val serverColumnUnit = ColumnUnit.CODE_POINT
                override val serverFirstColumn = firstColumn
                override val submitListener = object : PipeSubmitListener {
                    override fun beforeSubmit(run: PipeRun) {
                        runs += "before"
                        observed = run
                    }
                    override fun afterSubmit(run: PipeRun) {
                        runs += "after"
                    }
                }
            }
            val sql = "FROM offline_rows |> WHERE id > 0"
            Run(dialect, sql).use { run ->
                val failure = IllegalStateException("producer failed")
                run.recording.onSubmit = { throw failure }
                assertSame(failure, runCatching { run.execute() }.exceptionOrNull())
                assertEquals(listOf("before", "after"), runs)
                val statement = observed!!.statements.single()!!
                assertEquals(sql, statement.source)
                assertEquals(TextRange(0, sql.length), statement.range)
                val lines = statement.sql.lines()
                val line = lines.indexOfFirst { "offline_rows" in it }
                val column = lines[line].indexOf("offline_rows") + firstColumn
                assertEquals("offline_rows", statement.sourceMap!!(line + 1, column)!!.substring(statement.source))
            }
        }
    }

    fun testSourceMapIsUnavailableAfterParameterExpansion() {
        for (parameterized in listOf(false, true)) {
            var observed = false
            val dialect = object : PipeDialect by TestPipeDialect("trino", FirstStageRule.AnyQuery) {
                override val submitListener = object : PipeSubmitListener {
                    override fun beforeSubmit(run: PipeRun) {
                        observed = true
                        val map = run.statements.single()!!.sourceMap
                        if (parameterized) assertNull(map) else assertNotNull(map)
                        throw SqlExecutionRefusal("Source-map inspection only")
                    }
                }
            }
            Run(dialect, if (parameterized) "FROM offline_rows |> WHERE id = :id" else "FROM offline_rows |> SELECT id").use { run ->
                ShowSqlParametersPanelAction.getStorage(run.recording.console).putValue("id", "17")
                run.execute()
                assertTrue(observed)
                assertEmpty(run.recording.requests)
            }
        }
    }

    fun testSubmitHooksBracketTheFinalChecks() {
        var before: (PipeRun) -> Unit = {}
        var afters = 0
        val dialect = object : PipeDialect by TestPipeDialect("trino", FirstStageRule.AnyQuery) {
            override val submitListener = object : PipeSubmitListener {
                override fun beforeSubmit(run: PipeRun) = before(run)
                override fun afterSubmit(run: PipeRun) {
                    afters++
                }
            }
        }
        val changes = listOf<(PipeRun) -> Unit>(
            { it.console.dataSource.url = "jdbc:offline:another-destination" },
            { WriteCommandAction.runWriteCommandAction(project) { it.editor.document.insertString(0, " ") } },
        )
        for (change in changes) Run(dialect, "FROM offline_rows |> WHERE id > 0").use { run ->
            before = change
            run.execute()
            assertEmpty(run.recording.requests)
            assertTrue(run.notifications.any { it.type == NotificationType.ERROR })
        }
        Run(dialect, "FROM offline_rows |> WHERE id > 0").use { run ->
            val failure = IllegalStateException("listener failed")
            before = { throw failure }
            assertSame(failure, runCatching { run.execute() }.exceptionOrNull())
            assertEmpty(run.recording.requests)
        }
        assertEquals(3, afters)
    }

    fun testInspectionHostRefusesExecuteAndHidesRunToStage() {
        val dialect = object : PipeDialect by TestPipeDialect("trino", FirstStageRule.AnyQuery) {
            override fun isInspectionHost(file: PsiFile) = true
        }
        Run(dialect, "FROM offline_rows |> WHERE id > 0").use { run ->
            run.execute()
            assertEmpty(run.recording.requests)
            assertEmpty(run.delegated)
            assertEquals(NotificationType.WARNING, run.notifications.single().type)
            assertFalse(object : PipeRunToStageIntention(dialect) {}.isAvailable(project, myFixture.editor, run.file))
        }
    }

    fun testVetoDelegatesAndDisablesEditing() {
        val dialect = TestPipeDialect("trino", FirstStageRule.AnyQuery)
        System.setProperty(dialect.vetoProperty, "false")
        try {
            Run(dialect, "FROM offline_rows |> WHERE id > 0").use { run ->
                run.execute()
                assertEmpty(run.recording.requests)
                assertEquals(1, run.delegated.size)
                assertFalse(dialect.isEditingEnabled(run.file))
            }
        } finally {
            System.clearProperty(dialect.vetoProperty)
        }
    }

    private inner class Run(private val dialect: PipeDialect, sql: String) : AutoCloseable {
        private val virtualFile = myFixture.configureByText("pipe-${UUID.randomUUID()}.sql", sql).virtualFile
        val recording = RecordingConsole(project, virtualFile, TEST_DRIVER)
        val file: PsiFile get() = myFixture.file
        val delegated = mutableListOf<AnActionEvent>()
        val notifications = mutableListOf<Notification>()
        val queries get() = recording.requests.map { (it as DataRequest.QueryRequest).query }
        private val connection = project.messageBus.connect()

        init {
            connection.subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == dialect.notificationGroup) notifications += notification.apply { setSuppressShowingPopup(true) }
                }
            })
        }

        fun execute() {
            myFixture.editor.selectionModel.setSelection(0, myFixture.editor.document.textLength)
            val previous = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    delegated += e
                }
            }
            CooperativeExecuteAction(1, previous, PipeExecuteInterceptor(dialect)).actionPerformed(event())
        }

        fun runToStage() {
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.lastIndexOf("SELECT"))
            object : PipeRunToStageIntention(dialect) {}.invoke(project, myFixture.editor, file)
        }

        fun prompting(during: () -> Unit = {}, value: String = "7", run: () -> Unit) {
            var prompted = false
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) {
                    prompted = true
                    ShowSqlParametersPanelAction.getStorage(recording.console).putValue("id", value)
                    during()
                    component.close(DialogWrapper.OK_EXIT_CODE)
                }
            })
            run()
            assertTrue("Parameters were not prompted for: ${notifications.map { it.content }}", prompted)
        }

        private fun event(): AnActionEvent = AnActionEvent.createEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, myFixture.editor)
                .add(CommonDataKeys.PSI_FILE, file)
                .add(CommonDataKeys.VIRTUAL_FILE, virtualFile)
                .add(SessionClientHolder.CLIENT_KEY, recording.console)
                .build(),
            Presentation(), "PipeExecutionTest", ActionUiKind.NONE, null,
        )

        override fun close() {
            connection.disconnect()
            notifications.forEach { it.expire() }
            recording.close()
        }
    }
}
