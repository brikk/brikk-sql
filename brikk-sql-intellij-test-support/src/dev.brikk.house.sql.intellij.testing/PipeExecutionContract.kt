package dev.brikk.house.sql.intellij.testing

import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.model.ObjectKind
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.settings.DatabaseSettings
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.ui.UiInterceptors
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.pipe.FirstStageRule
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor
import dev.brikk.house.sql.intellij.pipe.PipeRunToStageAction
import dev.brikk.house.sql.intellij.pipe.PipeRunToStageIntention
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.lower
import java.util.concurrent.CancellationException

/**
 * Execution contracts of a [PipeDialect], checked through the real Execute chain, parameter
 * prompting and a [RecordingConsole] for [driverId], whose sessions the dialect must own; it must not
 * own [foreignDriverId]'s. Call each check from a light platform test with PIPE enabled; a failed
 * check throws [AssertionError]. [setEnabled] changes the project's PIPE setting and [open] shows SQL
 * in an editor as a file of the dialect's language; undo their effects in the test.
 */
public class PipeExecutionContract(
    private val dialect: PipeDialect,
    private val driverId: String,
    private val foreignDriverId: String,
    private val setEnabled: (Boolean) -> Unit,
    private val open: (sql: String) -> Editor,
) {
    /** With the setting off, or in a console the dialect does not own, Execute delegates once and says nothing. */
    public fun declinesWhenNotOwned() {
        for (foreign in listOf(false, true)) Run(PROGRAM).use { run ->
            if (foreign) run.recording.replaceDataSource(foreignDriverId) else setEnabled(false)
            try {
                val event = run.execute()
                expect(run.delegated == listOf(event)) { "Unowned PIPE (foreign=$foreign) must delegate once with the same event" }
            } finally {
                setEnabled(true)
            }
            expectNoRequests(run, "unowned PIPE")
            expect(run.notifications.isEmpty()) { "Unowned PIPE must not be notified: ${run.messages}" }
        }
    }

    /** The caret, selection and Execute options choose the same statements as stock Execute, and claimed ones lower. */
    public fun selectsStatementsLikeStockExecute() {
        Run("SELECT 1; FROM offline_rows |> LIMIT 2; SELECT 3;").use { run ->
            run.editor.caretModel.moveToOffset(run.editor.document.text.indexOf("LIMIT"))
            run.execute(execOption())
            expectQueries(run, listOf(lowered("FROM offline_rows |> LIMIT 2")), "the statement at the caret")
        }
        Run("SELECT 1; FROM offline_rows |> SELECT id; -- caret outside statements").use { run ->
            run.editor.caretModel.moveToOffset(run.editor.document.textLength)
            run.execute(execOption(outside = DatabaseSettings.EXECUTE_OUTSIDE_WHOLE_SCRIPT))
            run.finishRequests(2)
            expectQueries(run, listOf("SELECT 1", lowered("FROM offline_rows |> SELECT id")), "outside statements, whole script")
        }
        Run("SELECT 0; /* ) caret here */ FROM offline_rows |> SELECT id;").use { run ->
            run.editor.caretModel.moveToOffset(run.editor.document.text.indexOf("caret"))
            run.execute(execOption(outside = DatabaseSettings.EXECUTE_OUTSIDE_SCRIPT_TAIL))
            // Which statements the tail starts with is the dialect PSI's call; slicing the comment must not break the claim.
            val case = "outside statements, script tail from inside a comment"
            expect(run.queries.isNotEmpty() && run.queries.none { "|>" in it }) { "$case submitted ${run.queries}; ${run.messages}" }
            expect(run.delegated.isEmpty() && run.notifications.isEmpty()) { "$case must neither delegate nor refuse: ${run.messages}" }
        }
        Run(PROGRAM).use { run ->
            val text = run.editor.document.text
            run.editor.selectionModel.setSelection(text.indexOf("WHERE"), text.indexOf(" |> SELECT"))
            run.execute(execOption(selection = DatabaseSettings.EXECUTE_SELECTION_SMART_EXPAND))
            expectQueries(run, listOf(lowered(PROGRAM)), "a smart-expanded selection")
        }
        Run("SELECT 1;\nFROM offline_rows |> SELECT id;\nSELECT 2;").use { run ->
            run.editor.selectionModel.setSelection(0, run.editor.document.textLength)
            run.execute(execOption(selection = DatabaseSettings.EXECUTE_SELECTION_EXACTLY_SCRIPT))
            run.finishRequests(3)
            expectQueries(run, listOf("SELECT 1", lowered("FROM offline_rows |> SELECT id"), "SELECT 2"), "a mixed selection")
        }
    }

    /** A later failing PIPE statement or a missing console client submits nothing, not even earlier statements. */
    public fun refusesWholeScriptsItCannotRun() {
        Run("SELECT 1; FROM offline_rows |> BOGUS 2;").use { run ->
            run.editor.selectionModel.setSelection(0, run.editor.document.textLength)
            run.execute(DatabaseSettings.ExecOption().apply { execSelection = DatabaseSettings.EXECUTE_SELECTION_EXACTLY_SCRIPT })
            expectRefused(run, "a script with a later untranslatable statement")
        }
        Run(PROGRAM).use { run ->
            run.recording.console.session.detach(run.recording.console)
            run.execute()
            expectRefused(run, "a console without a client")
        }
    }

    /** Ordinary SQL, quoted markers included, delegates unchanged; PIPE SQL submits its lowering in every Execute variant. */
    public fun claimsOnlyPipes() {
        for (sql in listOf("SELECT 1", "SELECT '|>' AS a", "SELECT /* |> */ 1")) Run(sql).use { run ->
            val event = run.execute()
            expect(run.delegated == listOf(event)) { "$sql must delegate once with the same event" }
            expectNoRequests(run, sql)
        }
        val sql = "FROM offline_rows |> SELECT id |> WHERE id > 0"
        Run(sql).use { run ->
            for (variant in 1..4) {
                run.execute(variant)
                expect(run.queries == List(variant) { lowered(sql) }) { "Execute variant $variant: ${run.queries} ${run.messages}" }
                expect(run.recording.requests.all { it.owner === run.recording.console }) { "Requests must belong to the console" }
            }
            expect(run.delegated.isEmpty()) { "Claimed PIPE must never delegate" }
        }
    }

    /** An edit during the stock save, even one reverted, or during parameter prompting submits nothing and is notified. */
    public fun refusesStaleInput() {
        for (revert in listOf(false, true)) Run(PROGRAM).use { run ->
            var edited = false
            val connection = ApplicationManager.getApplication().messageBus.connect()
            connection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun beforeAllDocumentsSaving() {
                    if (edited) return
                    edited = true
                    run.edit(revert)
                }
            })
            try {
                run.execute()
            } finally {
                connection.disconnect()
            }
            expect(edited) { "The stock save was not reached" }
            expectRefused(run, "edit during the stock save (revert=$revert)")
        }
        for (entry in Entry.entries) Run(PARAMETERIZED).use { run ->
            run.whilePrompting(entry) { run.edit(revert = false) }
            expectRefused(run, "edit during parameter prompting via $entry")
        }
    }

    /** Cancellation during preparation propagates unchanged, submits nothing, and is not notified. */
    public fun propagatesCancellation() {
        for (cancellation in listOf(ProcessCanceledException(), CancellationException())) Run(PROGRAM).use { run ->
            run.recording.beforeSessionCall = { if (it == "getClientsWithFile") throw cancellation }
            val thrown = runCatching { run.execute() }.exceptionOrNull()
            expect(thrown === cancellation) { "Expected $cancellation to propagate, got $thrown" }
            expectNoRequests(run, "cancelled preparation")
            expect(run.notifications.isEmpty() && run.delegated.isEmpty()) { "Cancellation must not notify or delegate" }
        }
    }

    /** A statement whose only pipe is nested executes its lowering, selected or found at the caret. */
    public fun lowersNestedOnlyPipelines() {
        val sql = "SELECT id FROM (FROM offline_rows |> WHERE id > 0) AS s"
        Run(sql).use { run ->
            run.execute()
            expect(run.queries == listOf(lowered(sql))) { "Nested-only pipeline: ${run.queries} ${run.messages}" }
        }
        Run("SELECT 1; $sql; SELECT 2;").use { run ->
            run.editor.caretModel.moveToOffset(run.editor.document.text.indexOf("WHERE"))
            run.execute(execOption())
            expectQueries(run, listOf(lowered(sql)), "the nested-only pipeline at the caret")
        }
    }

    /**
     * Run-to-stage submits exactly the top-level prefix through the caret's stage, never a later or
     * nested stage, and runs a bare first stage only as [PipeDialect.firstStage] allows.
     */
    public fun runsTopLevelStagesToTheCaret() {
        val sql = "FROM offline_rows |> WHERE id > 0 |> SELECT id |> LIMIT 2"
        Run(sql).use { run ->
            run.editor.caretModel.moveToOffset(sql.indexOf("SELECT"))
            for (entry in Entry.runToStage) run.runToStage(entry)
            val prefix = "FROM offline_rows |> WHERE id > 0 |> SELECT id"
            expect(run.queries == List(2) { lowered(prefix) }) { "Run-to-stage: ${run.queries} ${run.messages}" }
        }
        val firstStages = listOf(
            "FROM offline_rows |> WHERE id > 0" to "FROM offline_rows",
            "SELECT id FROM offline_rows |> WHERE id > 0" to "SELECT id FROM offline_rows",
        )
        for ((program, stage) in firstStages) Run(program).use { run ->
            run.editor.caretModel.moveToOffset(2)
            run.runToStage(Entry.Intention)
            if (stage.startsWith("FROM") || dialect.firstStage == FirstStageRule.AnyQuery) {
                expect(run.queries == listOf(lowered(stage, firstStage = true))) { "First stage of $program: ${run.queries} ${run.messages}" }
            } else {
                expectRefused(run, "first stage of $program")
            }
        }
        val nested = "FROM offline_rows |> WHERE id IN (FROM other_rows |> WHERE id > 0 |> SELECT id) |> SELECT id"
        val nestedOnly = "SELECT id FROM (FROM offline_rows |> WHERE id > 0) AS s"
        for ((program, caret) in listOf(nested to nested.indexOf("WHERE id > 0"), nestedOnly to nestedOnly.indexOf("WHERE"))) {
            Run(program).use { run ->
                run.editor.caretModel.moveToOffset(caret)
                for (entry in Entry.runToStage) run.runToStage(entry)
                expectRefused(run, "run-to-stage inside a nested pipeline of $program")
            }
        }
    }

    /** A destination change during parameter prompting submits nothing through either entry point. */
    public fun refusesDestinationChangesDuringPrompting() {
        for (entry in Entry.entries) {
            Run(PARAMETERIZED).use { run ->
                run.whilePrompting(entry) {}
                expect(run.queries.size == 1) { "Prompted $entry must submit when nothing changes: ${run.messages}" }
            }
            Run(PARAMETERIZED).use { run ->
                run.whilePrompting(entry) { run.recording.console.dataSource.url = "jdbc:offline:another-destination" }
                expectRefused(run, "URL change during prompting via $entry")
            }
        }
    }

    /**
     * A switch of the console's current namespace during parameter prompting submits nothing; an unchanged
     * namespace runs, and so does one that could not be read before preparation.
     */
    public fun refusesNamespaceChangesDuringPrompting() {
        for (entry in Entry.entries) {
            Run(PARAMETERIZED).use { run ->
                run.switchNamespace("lake", "web")
                run.whilePrompting(entry) { run.switchNamespace("lake", "web") }
                expect(run.queries.size == 1) { "Prompted $entry must submit when the namespace stays: ${run.messages}" }
            }
            Run(PARAMETERIZED).use { run ->
                run.switchNamespace("lake", "web")
                run.whilePrompting(entry) { run.switchNamespace("lake", "other") }
                expectRefused(run, "namespace switch during prompting via $entry")
            }
            Run(PARAMETERIZED).use { run ->
                run.whilePrompting(entry) { run.switchNamespace("lake", "web") }
                expect(run.queries.size == 1) { "Prompted $entry must submit when the namespace was unknown: ${run.messages}" }
            }
        }
    }

    /** Untranslatable PIPE is notified as an error, never thrown, and never submits or delegates. */
    public fun notifiesRefusals() {
        Run("FROM offline_rows |> BOGUS 2").use { run ->
            val thrown = runCatching { run.execute() }.exceptionOrNull()
            expect(thrown == null) { "A refusal must not throw: $thrown" }
            expectRefused(run, "untranslatable Execute")
        }
        val sql = "FROM offline_rows |> BOGUS 2 |> LIMIT 1"
        Run(sql).use { run ->
            run.editor.caretModel.moveToOffset(sql.indexOf("BOGUS"))
            val thrown = runCatching { run.runToStage(Entry.Intention) }.exceptionOrNull()
            expect(thrown == null) { "A refusal must not throw: $thrown" }
            expectRefused(run, "untranslatable run-to-stage prefix")
        }
    }

    private fun lowered(sql: String, firstStage: Boolean = false): String =
        when (val translation = dialect.lower(sql, firstStage)) {
            is PipeTranslation.Lowered -> translation.sql.takeIf { translation.blocker == null }
            else -> null
        } ?: throw AssertionError("The contract needs $sql to lower cleanly in ${dialect.dialect}")

    private fun expectRefused(run: Run, case: String) {
        expectNoRequests(run, case)
        expect(run.delegated.isEmpty()) { "$case must not delegate" }
        expect(run.notifications.any { it.type == NotificationType.ERROR }) { "$case must be notified as an error" }
    }

    private fun expectQueries(run: Run, expected: List<String>, case: String) {
        val queries = run.queries.map { it.trim().removeSuffix(";").trim() }
        expect(queries == expected.map { it.trim() }) { "$case submitted $queries, expected $expected; ${run.messages}" }
        expect(run.delegated.isEmpty()) { "$case must not delegate" }
    }

    private fun execOption(
        outside: Int = DatabaseSettings.EXECUTE_OUTSIDE_NOTHING,
        selection: Int = DatabaseSettings.EXECUTE_SELECTION_EXACTLY_ONE,
    ) = DatabaseSettings.ExecOption().apply {
        execInside = DatabaseSettings.EXECUTE_INSIDE_SMALLEST
        execOutside = outside
        execSelection = selection
    }

    private fun expectNoRequests(run: Run, case: String) = expect(run.recording.requests.isEmpty()) { "$case submitted ${run.queries}" }

    private enum class Entry {
        Execute, Intention, Menu;

        companion object {
            val runToStage = listOf(Intention, Menu)
        }
    }

    private inner class Run(sql: String) : AutoCloseable {
        val editor = open(sql)
        private val project = requireNotNull(editor.project) { "The editor needs a project" }
        val recording = RecordingConsole(project, requireNotNull(FileDocumentManager.getInstance().getFile(editor.document)), driverId)
        val delegated = mutableListOf<AnActionEvent>()
        val notifications = mutableListOf<Notification>()
        val queries get() = recording.requests.map { (it as DataRequest.QueryRequest).query }
        val messages get() = notifications.map { it.content }
        private val file: PsiFile get() = requireNotNull(PsiDocumentManager.getInstance(project).getPsiFile(editor.document))
        private val connection = project.messageBus.connect()

        init {
            connection.subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId != dialect.notificationGroup) return
                    notification.setSuppressShowingPopup(true)
                    notifications += notification
                }
            })
        }

        /** Executes the whole document as a selection, so the check does not depend on the dialect's statement PSI. */
        fun execute(variant: Int = 1): AnActionEvent {
            editor.selectionModel.setSelection(0, editor.document.textLength)
            return perform(variant)
        }

        /** Executes from the current caret and selection with [option] as the first Execute variant's setting. */
        fun execute(option: DatabaseSettings.ExecOption) {
            val options = DatabaseSettings.getSettings().execOptions
            val saved = options[0]
            options[0] = option
            try {
                perform(1)
            } finally {
                options[0] = saved
            }
        }

        /** Completes submitted requests until [count] were submitted; the console submits a script one statement at a time. */
        fun finishRequests(count: Int) {
            while (recording.requests.size in 1 until count) {
                val before = recording.requests.size
                (recording.requests.last() as ConsoleDataRequest).onFinished()
                if (recording.requests.size == before) break
            }
        }

        private fun perform(variant: Int): AnActionEvent = event().also { event ->
            val previous = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    delegated += e
                }
            }
            CooperativeExecuteAction(if (variant == 4) -1 else variant, previous, PipeExecuteInterceptor(dialect), variant == 4)
                .actionPerformed(event)
        }

        fun runToStage(entry: Entry) {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            when (entry) {
                Entry.Intention -> object : PipeRunToStageIntention(dialect) {}.let {
                    expect(it.isAvailable(project, editor, file)) { "Run-to-stage intention unavailable" }
                    it.invoke(project, editor, file)
                }
                Entry.Menu -> object : PipeRunToStageAction(dialect) {}.let {
                    val event = event()
                    it.update(event)
                    expect(event.presentation.isEnabledAndVisible) { "Run-to-stage action unavailable" }
                    it.actionPerformed(event)
                }
                Entry.Execute -> execute()
            }
        }

        /** Runs [entry] on a statement whose `:id` parameter is prompted for, applying [change] in the dialog. */
        /** Makes [catalog].[schema] the console's current namespace, as the platform does after a switch or a read-back. */
        fun switchNamespace(catalog: String, schema: String) {
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create(catalog, ObjectKind.DATABASE).append(schema, ObjectKind.SCHEMA)))
        }

        fun whilePrompting(entry: Entry, change: () -> Unit) {
            var prompted = false
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) {
                    prompted = true
                    ShowSqlParametersPanelAction.getStorage(recording.console).putValue("id", "7")
                    change()
                    component.close(DialogWrapper.OK_EXIT_CODE)
                }
            })
            try {
                editor.caretModel.moveToOffset(editor.document.text.indexOf("SELECT"))
                runToStage(entry)
            } finally {
                if (!prompted) UiInterceptors.clear()
            }
            expect(prompted) { "Parameters were not prompted for via $entry: $messages" }
        }

        fun edit(revert: Boolean) = WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(0, " ")
            if (revert) editor.document.deleteString(0, 1)
        }

        private fun event(): AnActionEvent = AnActionEvent.createEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.PSI_FILE, file)
                .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                .add(SessionClientHolder.CLIENT_KEY, recording.console)
                .build(),
            Presentation(), "PipeExecutionContract", ActionUiKind.NONE, null,
        )

        override fun close() {
            connection.disconnect()
            notifications.forEach { it.expire() }
            recording.close()
        }
    }
}

private const val PROGRAM = "FROM offline_rows |> WHERE id > 0 |> SELECT id"
private const val PARAMETERIZED = "FROM offline_rows |> WHERE id = :id |> SELECT id"

private inline fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}
