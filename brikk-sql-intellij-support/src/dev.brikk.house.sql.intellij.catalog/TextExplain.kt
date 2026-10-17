package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.console.JdbcConsole
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.settings.DatabaseSettings
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.ObjectPath
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
import dev.brikk.house.sql.shape.ExplainableStatements
import dev.brikk.house.sql.shape.explainPlanText
import dev.brikk.house.sql.shape.prepareTextExplain
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean
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

/** A dialect's text explain: which consoles, how its SQL is read, and how a helper connection reaches the console's namespace. */
public interface TextExplainProfile {
    public val dbms: Dbms

    /** The product name in user-visible text, such as `Doris`. */
    public val productLabel: String

    /** Core dialect name, as `prepareTextExplain` accepts it (Trino and Doris explain; others refuse). */
    public val dialect: String

    /** Unused: text explain no longer checks which functions a query calls. */
    @Deprecated("Unused: text explain no longer checks which functions a query calls; remove the override.")
    public val builtinFunctions: FunctionCatalog? get() = null

    /** Text explain for the dialect's consoles. Off until a dialect enables it. */
    public val enabled: Boolean get() = false

    /** Which consoles are this dialect's: those whose data source's dbms it accepts. */
    public val dataSources: DialectDataSourceResolver get() = DialectDataSourceResolver(dbms)

    /** Switches the helper connection to the console's current namespace; null leaves the data source's default. */
    public val searchPath: CatalogSearchPathStrategy? get() = null

    /** Lowers PIPE statements first when PIPE is enabled for the project. */
    public val pipe: PipeDialect? get() = null

    /** Which statements are explained: plain queries, until a live engine proves another set writes nothing. */
    public val explainableStatements: ExplainableStatements get() = ExplainableStatements.Queries

    /** How long connecting, switching and explaining may take together. */
    public val timeout: Duration get() = 60.seconds

    /**
     * Driver properties the helper connection to [dataSource] sets over the data source's own and its
     * URL's ([HelperConnectionPoint]), as for verification; the data source is not changed.
     */
    public fun connectionProperties(dataSource: LocalDataSource): Map<String, String> = emptyMap()
}

/** What [TextExplainService.explain] gives. */
public sealed interface ExplainOutcome {
    public data class Plan(val text: String) : ExplainOutcome

    /** Not run, because of [reason]. */
    public data class Refused(val reason: String) : ExplainOutcome

    /** Run, but it failed with [message]. */
    public data class Failed(val message: String) : ExplainOutcome
}

/**
 * Explains a console's statement as text: the statement (PIPE lowered first) is prepared by core's
 * `prepareTextExplain`, which explains only queries (no DML, DDL or `CREATE TABLE AS`) and refuses
 * parameters. It runs on a new helper connection to the console's data source, never on the console's
 * session: the session's transaction, settings and namespace stay untouched. The helper first switches
 * to the console's current namespace through the profile's [CatalogSearchPathStrategy]. The result shows in a
 * popup with Copy. Register a project service subclass with the platform's `(Project, CoroutineScope)`
 * constructor, and a [TextExplainConfiguration] subclass naming it.
 */
public abstract class TextExplainService(protected val project: Project, private val scope: CoroutineScope) {
    public abstract val profile: TextExplainProfile

    /** Whether [console] is one of the dialect's and text explain is on. */
    public fun owns(console: JdbcConsole): Boolean = profile.enabled && profile.dataSources.accepts(console.dataSource.dbms)

    /** Explains [text] as run in [console]. Cancellation propagates, cancelling a running statement. */
    public suspend fun explain(console: JdbcConsole, text: String): ExplainOutcome {
        // The console would prompt for these; they are resolved with Execute, before any connection.
        ideParameterPattern(text)?.let { return ExplainOutcome.Refused("The statement has parameters ($it): resolve them with Execute before EXPLAIN.") }
        val ready = when (val prepared = prepare(text)) {
            is ExplainPreparation.Ready -> prepared
            is ExplainPreparation.Refused -> return ExplainOutcome.Refused(prepared.reason)
        }
        val namespace = when (val current = console.currentNamespace?.takeIf { namesOf(it).isNotEmpty() }) {
            null -> null
            else -> {
                val names = namesOf(current).joinToString(".")
                val strategy = profile.searchPath
                    ?: return ExplainOutcome.Refused("The console's namespace ($names) cannot be set on another connection: the dialect has no search path strategy.")
                strategy.switchSql(SearchPath.of(current))
                    ?: return ExplainOutcome.Refused("The console's namespace ($names) cannot be switched to on another connection; choose a schema in the console.")
            }
        }
        return withTimeoutOrNull(profile.timeout) { runOnHelper(console, listOfNotNull(namespace), ready.sql) }
            ?: ExplainOutcome.Failed("EXPLAIN did not finish within ${profile.timeout}.")
    }

    /** The first of the IDE's user parameter patterns (Database settings) in force that matches [text]. */
    private fun ideParameterPattern(text: String): String? =
        DatabaseSettings.getSettings().userPatterns.patterns.orEmpty()
            .filter { it.inScripts || it.inLiterals }
            .firstOrNull { pattern -> runCatching { Regex(pattern.pattern).containsMatchIn(text) }.getOrDefault(false) }
            ?.pattern

    private fun namesOf(path: ObjectPath): List<String> =
        generateSequence(path) { it.parent }.mapNotNull { it.name?.takeIf(String::isNotBlank) }.toList()

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
        return prepareTextExplain(sql, profile.dialect, profile.explainableStatements)
    }

    private suspend fun runOnHelper(console: JdbcConsole, setup: List<String>, sql: String): ExplainOutcome = try {
        withHelperConnection(console) { connection -> runOn(connection, setup, sql) } ?: ExplainOutcome.Failed("No connection.")
    } catch (e: Exception) {
        if (e is ControlFlowException || e is CancellationException) throw e
        ExplainOutcome.Failed("No connection: ${e.message}")
    }

    /**
     * Runs [use] on a new helper connection to [console]'s data source and closes it afterwards; null when
     * none opens. The connection is never the console's, runs no startup script ([HelperConnectionPoint]),
     * and harvests skip it. Override to set it up differently.
     */
    protected open suspend fun <T> withHelperConnection(console: JdbcConsole, use: suspend (DatabaseConnectionCore) -> T): T? {
        val ref = DatabaseConnectionManager.getInstance().build(project, HelperConnectionPoint(console.dataSource, profile.connectionProperties(console.dataSource))).setRequestor(HelperRequestor()).create() ?: return null
        return ref.use { use(it.get()) }
    }

    /** Runs [setup] (the namespace switch), then [sql], unless cancelled. */
    private suspend fun runOn(connection: DatabaseConnectionCore, setup: List<String>, sql: String): ExplainOutcome {
        val running = AtomicReference<RemoteStatement?>()
        val cancelled = AtomicBoolean()
        // A statement registered after cancelling is cancelled at once.
        val register: (RemoteStatement?) -> Unit = { statement ->
            running.set(statement)
            if (statement != null && cancelled.get()) JdbcNativeUtil.performSafe { statement.cancel() }
        }
        return coroutineScope {
            // Cancelling (or the timeout) cancels the running statement, which a driver's blocking call may not notice otherwise.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.set(true)
                    running.get()?.let { JdbcNativeUtil.performSafe { it.cancel() } }
                }
            }
            try {
                runInterruptible(Dispatchers.IO) {
                    // A driver may return normally from a cancelled statement: nothing more is submitted after cancelling.
                    fun proceed() {
                        if (cancelled.get()) throw CancellationException("EXPLAIN was cancelled")
                    }
                    val seconds = profile.timeout.inWholeSeconds.toInt().coerceAtLeast(1)
                    for (statement in setup) {
                        proceed()
                        connection.executeStatement(statement, seconds, register, cancelled::get)
                    }
                    proceed()
                    val rows = connection.queryRows(sql, MAX_PLAN_ROWS, seconds, onStatement = register, isCancelled = cancelled::get)
                    proceed()
                    ExplainOutcome.Plan(explainPlanText(rows.map { it.values.firstOrNull() as? String }))
                }
            } catch (e: Exception) {
                if (e is ControlFlowException || e is CancellationException) throw e
                ExplainOutcome.Failed(e.message ?: e.toString())
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Explains [text] for [console] in the service's scope and shows the outcome. */
    public fun explainAndShow(console: JdbcConsole, text: String): Job = scope.launch {
        val outcome = explain(console, text)
        withContext(Dispatchers.EDT) { present(outcome) }
    }

    /** Shows [outcome] now, on the EDT. */
    internal fun show(outcome: ExplainOutcome) = present(outcome)

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
public class TextExplainInterceptor(private val service: Class<out TextExplainService>) : ActionInterceptor {
    override fun update(event: AnActionEvent): Boolean? = target(event)?.let { true }

    /** An owned console's Explain Plan is always handled here, also with nothing to explain. */
    override fun perform(event: AnActionEvent, delegate: () -> Unit): ExecutionClaim {
        val (explain, console) = target(event) ?: return ExecutionClaim.DECLINED
        val editor = event.getData(CommonDataKeys.EDITOR)
        val text = editor?.selectionModel?.selectedText?.takeIf(String::isNotBlank) ?: editor?.let {
            val document = it.document.text
            SqlScriptSnapshot.parse(document, explain.profile.dialect).at(it.caretModel.offset)?.let { range -> document.substring(range.start, range.end) }
        }
        if (text.isNullOrBlank()) explain.show(ExplainOutcome.Refused("There is no statement at the caret to explain.")) else explain.explainAndShow(console, text)
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
public abstract class TextExplainConfiguration(service: Class<out TextExplainService>) : CooperativeActionConfiguration() {
    override val actionIds: List<String> = listOf(EXPLAIN_PLAN)
    override val interceptor: ActionInterceptor = TextExplainInterceptor(service)
}
