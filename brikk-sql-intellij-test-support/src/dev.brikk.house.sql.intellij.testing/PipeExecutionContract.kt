package dev.brikk.house.sql.intellij.testing

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
import org.jetbrains.annotations.ApiStatus

/**
 * Execution contracts of a [PipeDialect], checked through the real Execute chain, parameter
 * prompting and a [RecordingConsole] for [driverId], whose sessions the dialect must own. Call each
 * check from a light platform test with PIPE enabled; a failed check throws [AssertionError].
 * [open] shows SQL in an editor as a file of the dialect's language; undo its setup in the test.
 */
@ApiStatus.Experimental
public class PipeExecutionContract(
    private val dialect: PipeDialect,
    private val driverId: String,
    private val open: (sql: String) -> Editor,
) {
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

    /** A statement whose only pipe is nested executes its lowering. */
    public fun lowersNestedOnlyPipelines() {
        val sql = "SELECT id FROM (FROM offline_rows |> WHERE id > 0) AS s"
        Run(sql).use { run ->
            run.execute()
            expect(run.queries == listOf(lowered(sql))) { "Nested-only pipeline: ${run.queries} ${run.messages}" }
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
        fun execute(variant: Int = 1): AnActionEvent = event().also { event ->
            editor.selectionModel.setSelection(0, editor.document.textLength)
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
