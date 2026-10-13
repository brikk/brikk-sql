package dev.brikk.house.sql.intellij.pipe

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.ui.LanguageTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.brikk.house.sql.intellij.SqlExecutionRefusal
import dev.brikk.house.sql.intellij.selectionExecuteOption
import dev.brikk.house.sql.parser.PipeStageCut
import dev.brikk.house.sql.parser.PipeStageSplitter
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JPanel
import org.jetbrains.annotations.ApiStatus

/** Alt+Enter preview of the SQL a PIPE statement lowers to. Subclass with a no-arg class. */
@ApiStatus.Experimental
public abstract class PipePreviewIntention(private val dialect: PipeDialect) : IntentionAction {
    final override fun getText(): String = "${dialect.label}: preview generated SQL"
    final override fun getFamilyName(): String = dialect.label
    final override fun startInWriteAction(): Boolean = false
    final override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && dialect.previewable(file, editor) != null
    final override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null && file != null) dialect.preview(file, editor)
    }
}

/** Alt+Enter run of the top-level stages up to the caret. Subclass with a no-arg class. */
@ApiStatus.Experimental
public abstract class PipeRunToStageIntention(private val dialect: PipeDialect) : IntentionAction {
    final override fun getText(): String = "${dialect.label}: run stages up to caret"
    final override fun getFamilyName(): String = dialect.label
    final override fun startInWriteAction(): Boolean = false
    final override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && dialect.runnable(file, editor) != null
    final override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null && file != null) dialect.runToStage(file, editor)
    }
}

/** Editor menu entry for [PipePreviewIntention]'s operation. Subclass with a no-arg class. */
@ApiStatus.Experimental
public abstract class PipePreviewAction(private val dialect: PipeDialect) : AnAction() {
    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    final override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.target { file, editor -> dialect.previewable(file, editor) } != null
    }
    final override fun actionPerformed(e: AnActionEvent) {
        e.target { file, editor -> dialect.preview(file, editor) }
    }
}

/** Editor menu entry for [PipeRunToStageIntention]'s operation. Subclass with a no-arg class. */
@ApiStatus.Experimental
public abstract class PipeRunToStageAction(private val dialect: PipeDialect) : AnAction() {
    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    final override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.target { file, editor -> dialect.runnable(file, editor) } != null
    }
    final override fun actionPerformed(e: AnActionEvent) {
        e.target { file, editor -> dialect.runToStage(file, editor) }
    }
}

private inline fun <T> AnActionEvent.target(operation: (PsiFile, Editor) -> T): T? {
    val file = getData(CommonDataKeys.PSI_FILE) ?: return null
    val editor = getData(CommonDataKeys.EDITOR) ?: return null
    return operation(file, editor)
}

/** The PIPE statement at the caret: [text] starts at [start] in the text it was read from. */
private class CaretStatement(val start: Int, val text: String, val boundaryError: String?)

private fun PipeDialect.caretStatement(text: String, caret: Int): CaretStatement? =
    statementAt(text, caret)?.takeIf { it.hasPipe }?.let { CaretStatement(it.start, text.substring(it.start, it.end), it.boundaryError) }

private fun PipeDialect.previewable(file: PsiFile, editor: Editor): CaretStatement? {
    if (!isEditingEnabled(file) || !enablesPreview(file)) return null
    val source = pipeText(file)
    return caretStatement(source.text, source.textOffset(editor.caretModel.offset))
}

/** Run-to-stage executes the console's own document, so injected and inspection-only SQL never qualify. */
private fun PipeDialect.runnable(file: PsiFile, editor: Editor): CaretStatement? =
    if (!file.language.isKindOf(language) || isInspectionHost(file) || !isEnabled(file.project) ||
        InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
    ) null else caretStatement(editor.document.text, editor.caretModel.offset)

/** The preview's title; inspection-only SQL says it does not run as it is. */
internal fun PipeDialect.previewTitle(generated: Boolean, inspectionOnly: Boolean): String =
    (if (generated) "$label: generated SQL" else "$label: execution blocked") + if (inspectionOnly) " (inspection only, not executable)" else ""

private fun PipeDialect.preview(file: PsiFile, editor: Editor) {
    val statement = previewable(file, editor) ?: return
    val (sql, diagnostics) = when (val translation = lower(statement.text, file = file)) {
        is PipeTranslation.Lowered -> translation.sql to translation.blocker
        is PipeTranslation.Refused -> null to translation.message
        PipeTranslation.NotPipe -> null to "Not a PIPE statement."
    }
    val content = JPanel(BorderLayout(0, JBUI.scale(4)))
    val focus = sql?.let { generated ->
        LanguageTextField(language, file.project, generated, false).apply { isViewer = true }.also { field ->
            val height = minOf(360, 80 + 18 * generated.lines().size)
            content.add(JBScrollPane(field).apply { preferredSize = JBUI.size(640, height) }, BorderLayout.CENTER)
            content.add(JPanel(BorderLayout()).apply {
                add(JButton("Copy").apply { addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(generated)) } }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
    }
    val notes = diagnostics?.let { JBTextArea(it).apply { isEditable = false; lineWrap = true; wrapStyleWord = true } }
    notes?.let {
        content.add(JBScrollPane(it).apply { preferredSize = JBUI.size(640, 100) }, if (sql == null) BorderLayout.CENTER else BorderLayout.NORTH)
    }
    JBPopupFactory.getInstance().createComponentPopupBuilder(content, focus ?: notes)
        .setTitle(previewTitle(diagnostics == null, isInspectionHost(file)))
        .setResizable(true).setMovable(true).setRequestFocus(true)
        .createPopup().showInBestPositionFor(editor)
}

private fun PipeDialect.runToStage(file: PsiFile, editor: Editor) {
    val statement = runnable(file, editor) ?: return
    val project = file.project
    refusalsNotified(project) {
        statement.boundaryError?.let { throw SqlExecutionRefusal(it) }
        val console = JdbcConsoleProvider.getValidConsole(project, file.viewProvider.virtualFile)
            ?: throw SqlExecutionRefusal("Run-to-stage needs this file's console. Run any statement once, then retry.")
        if (!ownsSession(console.session)) throw SqlExecutionRefusal("This file's console does not run $label.")
        val destination = PipeDestination(this, console)
        val prefix = when (val cut = PipeStageSplitter.topLevelPrefixAt(statement.text, editor.caretModel.offset - statement.start, dialect)) {
            is PipeStageCut.Prefix -> cut
            PipeStageCut.InsideNestedPipeline ->
                throw SqlExecutionRefusal("Run-to-stage runs top-level stages; the caret is inside a nested pipeline.")
            PipeStageCut.Unavailable -> throw SqlExecutionRefusal("No top-level PIPE stage of a multi-stage program is at the caret.")
        }
        val leading = statement.text.length - statement.text.trimStart().length
        val range = TextRange(statement.start + leading, statement.start + prefix.sql.length)
        val model = console.scriptModel.subModel(range) ?: throw SqlExecutionRefusal("No statement is at the caret.")
        execute(console, editor, model, selectionExecuteOption(), destination, firstStage = prefix.stage == 0)
    }
}
