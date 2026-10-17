package dev.brikk.house.sql.intellij.testing

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.jetbrains.annotations.ApiStatus

/**
 * A plugin's editor popup entry, checked by expanding the registered [parent] group as the IDE renders a popup:
 * the action or group registered as [item] is in the popup, with each of its [children] rendered, enabled and
 * visible, exactly where it applies, and absent (not disabled) everywhere else. The common recipe is a
 * `com.intellij.ide.actions.NonEmptyActionGroup` whose children set `isEnabledAndVisible` from their own
 * applicability; a plain group would stay as an empty submenu. Run [check] from a light platform test of the
 * plugin; every problem is reported together.
 */
@ApiStatus.Experimental
public class EditorPopupContract(
    private val fixture: CodeInsightTestFixture,
    private val item: String,
    private val children: List<String> = emptyList(),
    private val parent: String = IdeActions.GROUP_EDITOR_POPUP,
) {
    /**
     * [openValid] opens, in [fixture], an editor where the entry applies (the plugin's SQL, a PIPE statement). The
     * entry shows there, and is absent: with that file but no editor, with that editor but no file, in an empty
     * context, with that file and an editor of another file, and in each of [unrelated] (file name to text), with
     * and without a selection.
     */
    public fun check(unrelated: Map<String, String> = mapOf("popup-notes.txt" to "SELECT 1"), openValid: () -> Unit) {
        val failures = ArrayList<String>()
        openValid()
        val file = fixture.file
        val editor = fixture.editor
        failures += problems(context(), shown = true, "the valid context")
        failures += problems(context(editor = null, file = file), shown = false, "the valid file without an editor")
        failures += problems(context(editor = editor, file = null), shown = false, "the valid editor without a file")
        failures += problems(DataContext.EMPTY_CONTEXT, shown = false, "an empty context")
        for ((name, text) in unrelated) {
            fixture.configureByText(name, text)
            failures += problems(context(), shown = false, name)
            failures += problems(context(file = file), shown = false, "the valid file with the editor of $name")
            fixture.editor.selectionModel.setSelection(0, fixture.editor.document.textLength)
            failures += problems(context(), shown = false, "$name with a selection")
        }
        if (failures.isNotEmpty()) throw AssertionError("${failures.size} editor popup problem(s) for $item:\n" + failures.joinToString("\n"))
    }

    /** The editor popup's data context: the fixture's project with [editor] and [file], each absent when null. */
    public fun context(editor: Editor? = fixture.editor, file: PsiFile? = fixture.file): DataContext =
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, fixture.project)
            .apply {
                if (editor == null) addNull(CommonDataKeys.EDITOR) else add(CommonDataKeys.EDITOR, editor)
                if (file == null) addNull(CommonDataKeys.PSI_FILE) else add(CommonDataKeys.PSI_FILE, file)
            }
            .build()

    /** Asserts the entry and its children show, enabled, in [context]. */
    public fun assertShown(context: DataContext = context(), what: String = "the editor's context") {
        problems(context, shown = true, what).takeIf { it.isNotEmpty() }?.let { throw AssertionError(it.joinToString("\n")) }
    }

    /** Asserts the entry and its children are absent from the popup in [context]. */
    public fun assertAbsent(context: DataContext = context(), what: String = "the editor's context") {
        problems(context, shown = false, what).takeIf { it.isNotEmpty() }?.let { throw AssertionError(it.joinToString("\n")) }
    }

    private fun problems(context: DataContext, shown: Boolean, what: String): List<String> {
        val manager = ActionManager.getInstance()
        val entry = checkNotNull(manager.getAction(item)) { "$item is not registered" }
        val popup = checkNotNull(manager.getAction(parent) as? ActionGroup) { "$parent is not a registered group" }
        val factory = PresentationFactory()
        val problems = ArrayList<String>()
        val rendered = expand(popup, context, factory).contains(entry)
        if (rendered != shown) problems += "$what: $item is ${if (rendered) "in" else "absent from"} $parent"
        if (shown && !factory.getPresentation(entry).isEnabledAndVisible) problems += "$what: $item is not enabled and visible"
        val expanded = (entry as? ActionGroup)?.let { expand(it, context, factory) }.orEmpty()
        for (id in children) {
            val child = checkNotNull(manager.getAction(id)) { "$id is not registered" }
            if (expanded.contains(child) != shown) problems += "$what: $id is ${if (shown) "absent from" else "in"} $item"
            if (factory.getPresentation(child).isEnabledAndVisible != shown) problems += "$what: $id is ${if (shown) "not " else ""}enabled and visible"
        }
        return problems
    }

    private fun expand(group: ActionGroup, context: DataContext, factory: PresentationFactory): List<AnAction> =
        Utils.expandActionGroup(group, factory, context, ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP)
}
