package dev.brikk.house.sql.intellij.pipe

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.model.ObjectKind
import com.intellij.database.script.ScriptModel
import com.intellij.database.script.translator.TranslateException
import com.intellij.database.settings.DatabaseSettings
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.brikk.house.sql.intellij.EditorSnapshot
import dev.brikk.house.sql.intellij.ExecuteInterceptor
import dev.brikk.house.sql.intellij.ExecutionClaim
import dev.brikk.house.sql.intellij.RenderedSql
import dev.brikk.house.sql.intellij.SqlExecutionAdapter
import dev.brikk.house.sql.intellij.SqlExecutionRefusal
import dev.brikk.house.sql.intellij.SqlParameterSpan
import dev.brikk.house.sql.intellij.STALE_SQL
import dev.brikk.house.sql.intellij.TranslatingScriptModel
import dev.brikk.house.sql.intellij.notifyEscaped
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import dev.brikk.house.sql.parser.sourceOffset
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.TranspileResult
import org.jetbrains.annotations.ApiStatus

/**
 * Claims Execute for PIPE input in the dialect's consoles and never lets claimed input fall back to
 * raw SQL. Destination identity is captured before preparation and rechecked before submission; the
 * edited document is pinned after the stock save. Refusals are notified, cancellation propagates.
 */
@ApiStatus.Experimental
public class PipeExecuteInterceptor(private val dialect: PipeDialect) : ExecuteInterceptor {
    override fun execute(event: AnActionEvent, option: DatabaseSettings.ExecOption): ExecutionClaim {
        val file = event.getData(CommonDataKeys.PSI_FILE)
        if (file != null && dialect.isInspectionHost(file)) {
            dialect.notifyRefusal(event.project, INSPECTION_HOST, NotificationType.WARNING)
            return ExecutionClaim.HANDLED
        }
        val console = JdbcConsole.findConsole(event) ?: return ExecutionClaim.DECLINED
        if (!dialect.isEnabled(console.project) || !dialect.ownsSession(console.session)) return ExecutionClaim.DECLINED
        val editor = event.getData(CommonDataKeys.EDITOR)
            ?: (event.getData(PlatformCoreDataKeys.FILE_EDITOR) as? TextEditor)?.editor ?: return ExecutionClaim.DECLINED
        val candidate = dialect.executionCandidate(editor, option)
        if (candidate == null || !dialect.containsPipe(candidate)) return ExecutionClaim.DECLINED
        val destination = PipeDestination(dialect, console)
        dialect.refusalsNotified(console.project) {
            dialect.scriptRanges(candidate).firstNotNullOfOrNull { it.boundaryError }?.let { throw SqlExecutionRefusal(it) }
            val preparation = Disposer.newDisposable("PIPE Execute preparation")
            try {
                val pinAfterSave = EditorSnapshot.captureAfterSave(editor.document, preparation)
                var snapshot: EditorSnapshot? = null
                object : RunQueryAction(1) {
                    // The stock method is OverrideOnly: invoke it from the corresponding override.
                    override fun actionPerformed(e: AnActionEvent) = super.actionPerformed(e)

                    // Stock preparation saves before asking for the option, and a save can reset
                    // the modification stamp without an edit.
                    override fun getExecOption(): DatabaseSettings.ExecOption = option.also { snapshot = pinAfterSave() }

                    override fun invokeImpl(e: AnActionEvent, console: JdbcConsole?, info: JdbcConsoleProvider.Info) {
                        if (console == null) return
                        dialect.refusalsNotified(console.project) {
                            val prepared = snapshot ?: throw SqlExecutionRefusal(STALE_SQL)
                            destination.requireCurrent(console)
                            prepared.requireCurrent()
                            JdbcConsoleProvider.chooseStatements(info, "Nothing to run", true) { selected ->
                                dialect.refusalsNotified(console.project) {
                                    prepared.requireCurrent()
                                    dialect.execute(console, editor, selected, info.execOption, destination)
                                }
                            }
                        }
                    }
                }.actionPerformed(event)
            } finally {
                Disposer.dispose(preparation)
            }
        }
        return ExecutionClaim.HANDLED
    }
}

/** Observes translated submissions, for example to map server errors back to authored PIPE text. */
@ApiStatus.Experimental
public interface PipeSubmitListener {
    /** Runs before the final destination and freshness checks; a refusal there still submits nothing. */
    public fun beforeSubmit(run: PipeRun) {}

    /** Runs after every [beforeSubmit], also when it, a final check or submission fails. */
    public fun afterSubmit(run: PipeRun) {}
}

/** One submission; [statements] follow the console's query order, null for statements not translated. */
@ApiStatus.Experimental
public class PipeRun internal constructor(public val console: JdbcConsole, public val editor: Editor, public val statements: List<PipeRunStatement?>)

/**
 * [range] is the authored statement in the document. [sourceMap] maps a server error position on
 * [sql] to a range in [source]; null when parameter expansion changed the input.
 */
@ApiStatus.Experimental
public class PipeRunStatement(
    public val source: String,
    public val sql: String,
    public val range: TextRange,
    public val sourceMap: ((line: Int, column: Int) -> TextRange?)?,
)

/**
 * The console target captured before preparation, which parameter prompting can change: console, session,
 * data source and URL, and the console's current namespace. That namespace is the one the dialect's search
 * path (D-I `CatalogSearchPathStrategy`) switched to or read back after the last execution, so comparing it
 * costs no round trip and runs no probe in the user's transaction; one unknown before or after never blocks.
 */
internal class PipeDestination(private val dialect: PipeDialect, private val console: JdbcConsole) {
    private val session = console.session
    private val dataSource = console.dataSource
    private val url = dataSource.url
    private val namespace = namespaceOf(console)

    fun requireCurrent(current: JdbcConsole) {
        if (!dialect.isEnabled(current.project)) throw SqlExecutionRefusal("PIPE was disabled during preparation.")
        if (current !== console || !current.isValid || current.session !== session || current.dataSource !== dataSource ||
            dataSource.url != url || !dialect.ownsSession(session) || namespaceChanged(current)
        ) throw SqlExecutionRefusal("The destination changed during preparation. Run the query again.")
    }

    private fun namespaceChanged(current: JdbcConsole): Boolean {
        val before = namespace ?: return false
        val now = namespaceOf(current) ?: return false
        return now != before
    }

    /** The current namespace, outermost first, by kind and name; null when the console does not know it. */
    private fun namespaceOf(console: JdbcConsole): List<Pair<ObjectKind, String>>? =
        generateSequence(console.currentNamespace) { it.parent }
            .mapNotNull { path -> path.name?.takeIf(String::isNotBlank)?.let { path.kind to it } }
            .toList().asReversed().ifEmpty { null }
}

/** Translates the claimed statements of [selected], then submits the whole model through [console]. */
internal fun <E> PipeDialect.execute(
    console: JdbcConsole,
    editor: Editor,
    selected: ScriptModel<E>,
    option: DatabaseSettings.ExecOption,
    destination: PipeDestination,
    firstStage: Boolean = false,
) = refusalsNotified(console.project) {
    val file = selected.virtualFile?.let { PsiManager.getInstance(console.project).findFile(it) }
    val adapter = PipeExecutionAdapter(this, firstStage, file)
    val model = TranslatingScriptModel(selected, editor, adapter)
    if (!console.beforeExecuteQueries(model)) return@refusalsNotified
    model.finalizeTranslations(console.pStorage)
    // Platform statement iterators are cursors: read each statement while it is current.
    val translated = buildList {
        for (statement in model.statements()) add(statement.takeIf { adapter.claims(it.text()) }?.let {
            val range = it.range().shiftRight(Math.toIntExact(it.rangeOffset()))
            PipeRunStatement(it.text(), it.query(), range, model.authoredMap(it.range(), it.rangeOffset()))
        })
    }
    validator?.takeIf { it.timing == ValidationTiming.Submission }?.let { check ->
        for (statement in translated.filterNotNull()) {
            val verdict = check.validate(statement.sql, file)
            if (verdict is NativeVerdict.Rejected) throw SqlExecutionRefusal("Generated SQL failed validation: ${verdict.message}")
        }
    }
    val listener = submitListener
    val run = PipeRun(console, editor, translated)
    try {
        listener?.beforeSubmit(run)
        destination.requireCurrent(console)
        if (!model.isActual) throw SqlExecutionRefusal(STALE_SQL)
        if (console.session.clientsWithFile.isNullOrEmpty()) throw SqlExecutionRefusal("No console client is attached.")
        ProgressManager.checkCanceled()
        console.executeQueries(editor, model, option)
    } finally {
        listener?.afterSubmit(run)
    }
}

private class PipeExecutionAdapter(
    private val dialect: PipeDialect,
    private val firstStage: Boolean,
    private val file: PsiFile?,
) : SqlExecutionAdapter {
    override fun claims(text: String): Boolean = firstStage || dialect.containsPipe(text)

    override fun parameters(text: String): List<SqlParameterSpan> = try {
        dialect.parameterSpans(text)
    } catch (e: ParseError) {
        throw SqlExecutionRefusal(e.message.orEmpty())
    } catch (e: TokenError) {
        throw SqlExecutionRefusal(e.message.orEmpty())
    } catch (e: ShapeError) {
        throw SqlExecutionRefusal(e.message.orEmpty())
    }

    override fun translate(text: String, beforeSubstitution: Boolean): RenderedSql =
        when (val translation = dialect.lower(text, firstStage, !beforeSubstitution, file)) {
            is PipeTranslation.Lowered -> {
                translation.blocker?.let { throw SqlExecutionRefusal(it) }
                RenderedSql(translation.sql, text, dialect.sourceMap(translation.result, text))
            }
            is PipeTranslation.Refused -> throw SqlExecutionRefusal(located(text, translation.range, translation.message))
            PipeTranslation.NotPipe -> throw SqlExecutionRefusal("The PIPE statement was not recognized, so it was not executed.")
        }
}

/** Maps a server position on generated SQL, in the dialect's column convention, to authored [text]. */
private fun PipeDialect.sourceMap(result: TranspileResult, text: String): (Int, Int) -> TextRange? = map@{ line, column ->
    val offset = sourceOffset(result.sql, line, column, serverColumnUnit, serverFirstColumn) ?: return@map null
    val utf16Column = offset - (result.sql.lastIndexOf('\n', offset - 1) + 1) + 1
    val position = result.mapErrorToSource(line, utf16Column) ?: return@map null
    if (position.start < 0 || position.end < position.start || position.end >= text.length) null
    else TextRange(position.start, position.end + 1)
}

private fun located(text: String, range: SyntacticRange?, message: String): String {
    val start = range?.start?.takeIf { it in 0..text.length } ?: return message
    val line = 1 + text.take(start).count { it == '\n' }
    val column = start - (text.lastIndexOf('\n', start - 1) + 1) + 1
    return "Line $line, column $column: $message"
}

/** The text the stock Execute variant would select; only an ownership precheck. */
internal fun PipeDialect.executionCandidate(editor: Editor, option: DatabaseSettings.ExecOption): String? {
    val text = editor.document.text
    val caret = editor.caretModel.offset
    val selection = editor.selectionModel
    selection.selectedText?.let { selected ->
        if (option.execSelection != DatabaseSettings.EXECUTE_SELECTION_SMART_EXPAND) return selected
        val expanded = scriptRanges(text).filter { it.start < selection.selectionEnd && it.end > selection.selectionStart }
        return if (expanded.isEmpty()) selected else text.substring(expanded.first().start, expanded.last().end)
    }
    val current = statementAt(text, caret)
    val tokens = current?.takeIf { it.boundaryError == null }?.let {
        Tokenizer(TokenizerConfigs.forName(dialect)).tokenizeForEditor(text.substring(it.start, it.end)).tokens
            .filterNot { token -> token.tokenType == TokenType.SEMICOLON }
    }
    val first = tokens?.firstOrNull()?.let { current.start + it.start }
    val last = tokens?.lastOrNull()?.let { current.start + it.end + 1 }
    val inside = current != null && current.hasSql && (tokens == null || first != null && last != null && caret in first..last)
    if (!inside) return when (option.execOutside) {
        DatabaseSettings.EXECUTE_OUTSIDE_WHOLE_SCRIPT -> text
        // Keep a leading comment whole: slicing it at the caret would invent broken SQL.
        DatabaseSettings.EXECUTE_OUTSIDE_SCRIPT_TAIL -> text.substring(
            if (current != null && first != null && caret <= first) current.start
            else scriptRanges(text).firstOrNull { it.hasSql && it.start >= caret }?.start ?: text.length,
        )
        else -> null
    }
    return when (option.execInside) {
        DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT,
        DatabaseSettings.EXECUTE_INSIDE_SHOW_CHOOSER,
        DatabaseSettings.EXECUTE_INSIDE_BATCH -> text
        DatabaseSettings.EXECUTE_INSIDE_SCRIPT_TAIL -> text.substring(current.start)
        else -> text.substring(current.start, current.end)
    }
}

internal fun PipeDialect.notifyRefusal(project: Project?, message: String, type: NotificationType = NotificationType.ERROR) =
    notifyEscaped(project, notificationGroup, label, message, type)

/** Expected refusals become notifications; cancellation and unexpected failures propagate. */
internal inline fun PipeDialect.refusalsNotified(project: Project, operation: () -> Unit) {
    try {
        operation()
    } catch (refusal: SqlExecutionRefusal) {
        notifyRefusal(project, refusal.message.orEmpty())
    } catch (failure: TranslateException) {
        notifyRefusal(project, failure.message ?: "Parameter substitution failed.")
    }
}

private const val INSPECTION_HOST: String =
    "This SQL is an inspection view. Execute the compiled relation with its bindings instead."
