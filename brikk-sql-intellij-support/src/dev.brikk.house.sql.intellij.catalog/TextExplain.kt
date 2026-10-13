package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.console.JdbcConsole
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.SearchPath
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.brikk.house.sql.intellij.ActionInterceptor
import dev.brikk.house.sql.intellij.CooperativeActionConfiguration
import dev.brikk.house.sql.intellij.ExecutionClaim
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.containsPipe
import dev.brikk.house.sql.intellij.pipe.isEnabled
import dev.brikk.house.sql.intellij.pipe.lower
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.shape.ExplainPreparation
import dev.brikk.house.sql.shape.explainPlanText
import dev.brikk.house.sql.shape.prepareTextExplain
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.ApiStatus

/** A dialect's text explain: which consoles, how its SQL is read, and how a helper connection reaches the console's namespace. */
@ApiStatus.Experimental
public interface TextExplainProfile {
    public val dbms: Dbms

    /** The product name in user-visible text, such as `Doris`. */
    public val productLabel: String

    /** Core dialect name, as `prepareTextExplain` accepts it (Trino and Doris explain; others refuse). */
    public val dialect: String

    /** The engine's built-in functions (the bundled catalog, not a live one): a query calling any other function is not explained. */
    public val builtinFunctions: FunctionCatalog

    /** Text explain for the dialect's consoles. Off until a dialect enables it. */
    public val enabled: Boolean get() = false

    /** Which consoles are this dialect's: those whose data source's dbms it accepts. */
    public val dataSources: DialectDataSourceResolver get() = DialectDataSourceResolver(dbms)

    /** Switches the helper connection to the console's current namespace; null leaves the data source's default. */
    public val searchPath: CatalogSearchPathStrategy? get() = null

    /** Lowers PIPE statements first when PIPE is enabled for the project. */
    public val pipe: PipeDialect? get() = null

    /** How long connecting, switching and explaining may take together. */
    public val timeout: Duration get() = 60.seconds
}

/** What [TextExplainService.explain] gives. */
@ApiStatus.Experimental
public sealed interface ExplainOutcome {
    public data class Plan(val text: String) : ExplainOutcome

    /** Not run, because of [reason]. */
    public data class Refused(val reason: String) : ExplainOutcome

    /** Run, but it failed with [message]. */
    public data class Failed(val message: String) : ExplainOutcome
}

/**
 * Explains a console's statement as text: the statement (PIPE lowered first) is prepared by core's
 * `prepareTextExplain`, which explains only plain queries that cannot run code while being planned. It
 * runs on a new helper connection to the console's data source, never on the console's session: the
 * session's transaction, settings and namespace stay untouched, and the helper first switches to the
 * console's current namespace through the profile's [CatalogSearchPathStrategy]. The result shows in a
 * popup with Copy. Register a project service subclass with the platform's `(Project, CoroutineScope)`
 * constructor, and a [TextExplainConfiguration] subclass naming it.
 */
@ApiStatus.Experimental
public abstract class TextExplainService(protected val project: Project, private val scope: CoroutineScope) {
    public abstract val profile: TextExplainProfile

    /** Whether [console] is one of the dialect's and text explain is on. */
    public fun owns(console: JdbcConsole): Boolean = profile.enabled && profile.dataSources.accepts(console.dataSource.dbms)

    /** Explains [text] as run in [console]. Cancellation propagates, cancelling a running statement. */
    public suspend fun explain(console: JdbcConsole, text: String): ExplainOutcome {
        val sql = when (val prepared = prepare(text)) {
            is ExplainPreparation.Ready -> prepared.sql
            is ExplainPreparation.Refused -> return ExplainOutcome.Refused(prepared.reason)
        }
        val namespace = console.currentNamespace?.let { current ->
            val strategy = profile.searchPath ?: return@let null
            strategy.switchSql(SearchPath.of(current)) ?: return ExplainOutcome.Refused("The console's namespace cannot be switched to on another connection.")
        }
        return withTimeoutOrNull(profile.timeout) { runOnHelper(console, namespace, sql) }
            ?: ExplainOutcome.Failed("EXPLAIN did not finish within ${profile.timeout}.")
    }

    private fun prepare(text: String): ExplainPreparation {
        val pipe = profile.pipe
        val sql = if (pipe == null || !pipe.isEnabled(project) || !pipe.containsPipe(text)) text else when (val lowered = pipe.lower(text)) {
            is PipeTranslation.Refused -> return ExplainPreparation.Refused(lowered.message)
            PipeTranslation.NotPipe -> return ExplainPreparation.Refused("The text holds PIPE syntax but is not a PIPE query.")
            is PipeTranslation.Lowered -> {
                lowered.blocker?.let { return ExplainPreparation.Refused(it) }
                if (pipe.parameterSpans(lowered.sql).isNotEmpty()) return ExplainPreparation.Refused("PIPE parameters must be resolved with Execute before EXPLAIN.")
                lowered.sql
            }
        }
        return prepareTextExplain(sql, profile.dialect, profile.builtinFunctions::isKnown)
    }

    private suspend fun runOnHelper(console: JdbcConsole, namespace: String?, sql: String): ExplainOutcome {
        val ref = try {
            DatabaseConnectionManager.getInstance().build(project, console.dataSource).setRequestor(ConnectionRequestor.Anonymous()).create()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExplainOutcome.Failed("No connection: ${e.message}")
        } ?: return ExplainOutcome.Failed("No connection.")
        return ref.use { helper ->
            val running = AtomicReference<RemoteStatement?>()
            coroutineScope {
                // Cancelling (or the timeout) cancels the running statement, which a driver's blocking call may not notice otherwise.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        running.get()?.let { JdbcNativeUtil.performSafe { it.cancel() } }
                    }
                }
                try {
                    runInterruptible(Dispatchers.IO) {
                        val seconds = profile.timeout.inWholeSeconds.toInt().coerceAtLeast(1)
                        namespace?.let { helper.get().executeStatement(it, seconds, running::set) }
                        ExplainOutcome.Plan(explainPlanText(helper.get().queryRows(sql, MAX_PLAN_ROWS, seconds, running::set).map { it.values.firstOrNull() }))
                    }
                } catch (e: Exception) {
                    if (e is ControlFlowException || e is CancellationException) throw e
                    ExplainOutcome.Failed(e.message ?: e.toString())
                } finally {
                    watcher.cancel()
                }
            }
        }
    }

    /** Explains [text] for [console] in the service's scope and shows the outcome. */
    public fun explainAndShow(console: JdbcConsole, text: String): Job = scope.launch {
        val outcome = explain(console, text)
        withContext(Dispatchers.EDT) { present(outcome) }
    }

    /** Shows [outcome]; by default a resizable monospaced popup with Copy All and Close. */
    protected open fun present(outcome: ExplainOutcome) {
        if (project.isDisposed) return
        val text = when (outcome) {
            is ExplainOutcome.Plan -> outcome.text.ifBlank { "EXPLAIN returned no rows." }
            is ExplainOutcome.Refused -> "EXPLAIN was not run:\n\n${outcome.reason}"
            is ExplainOutcome.Failed -> "EXPLAIN failed:\n\n${outcome.message}"
        }
        showPopup("${profile.productLabel} — Explain Plan", text)
    }

    private fun showPopup(title: String, text: String) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val area = JTextArea(text).apply {
            isEditable = false
            lineWrap = false
            font = Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
            border = JBUI.Borders.empty(8)
            caretPosition = 0
        }
        val scroll = JBScrollPane(area).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(JBUI.scale(920), JBUI.scale(560))
        }
        val copy = JButton("Copy All")
        val close = JButton("Close")
        val panel = JPanel(BorderLayout()).apply {
            add(scroll, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(6))).apply { add(copy); add(close) }, BorderLayout.SOUTH)
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, area)
            .setTitle(title).setResizable(true).setMovable(true).setRequestFocus(true).setFocusable(true)
            .setCancelOnClickOutside(false).setCancelKeyEnabled(true)
            .setMinSize(Dimension(JBUI.scale(480), JBUI.scale(280)))
            .createPopup()
        copy.addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(text)) }
        close.addActionListener { popup.cancel() }
        popup.showCenteredInCurrentWindow(project)
    }

    private companion object {
        const val MAX_PLAN_ROWS = 100_000
    }
}

/**
 * Claims Explain Plan in the profile's consoles, shows it there, and leaves every other console to the
 * wrapped action. The statement is the selection, else the statement at the caret.
 */
@ApiStatus.Experimental
public class TextExplainInterceptor(private val service: Class<out TextExplainService>) : ActionInterceptor {
    override fun update(event: AnActionEvent): Boolean? = target(event)?.let { true }

    override fun perform(event: AnActionEvent, delegate: () -> Unit): ExecutionClaim {
        val (explain, console) = target(event) ?: return ExecutionClaim.DECLINED
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return ExecutionClaim.DECLINED
        val selection = editor.selectionModel.selectedText?.takeIf(String::isNotBlank)
        val text = selection ?: run {
            val document = editor.document.text
            SqlScriptSnapshot.parse(document, explain.profile.dialect).at(editor.caretModel.offset)?.let { document.substring(it.start, it.end) }
        }
        if (text.isNullOrBlank()) return ExecutionClaim.DECLINED
        explain.explainAndShow(console, text)
        return ExecutionClaim.HANDLED
    }

    private fun target(event: AnActionEvent): Pair<TextExplainService, JdbcConsole>? {
        val project = event.project ?: return null
        val explain = project.getService(service) ?: return null
        val console = JdbcConsole.findConsole(event)?.takeIf(explain::owns) ?: return null
        return explain to console
    }
}

/** Wraps Explain Plan with [TextExplainInterceptor]. Register a no-arg subclass as `dynamicActionConfigurationCustomizer`. */
@ApiStatus.Experimental
public abstract class TextExplainConfiguration(service: Class<out TextExplainService>) : CooperativeActionConfiguration() {
    override val actionIds: List<String> = listOf(EXPLAIN_PLAN)
    override val interceptor: ActionInterceptor = TextExplainInterceptor(service)
}
