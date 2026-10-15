package dev.brikk.house.sql.intellij.testing

import com.intellij.ide.actions.NonEmptyActionGroup
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiDocumentManager
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.generic.GenericDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Applies to SQL in an editor showing that very file; hidden, not disabled, everywhere else. */
class ContractSqlAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = editor != null && file != null && file.language is SqlLanguageDialect &&
            PsiDocumentManager.getInstance(file.project).getDocument(file) == editor.document
    }

    override fun actionPerformed(e: AnActionEvent) = Unit
}

/** Applies anywhere: the popup clutter the contract exists to catch. */
class ContractEverywhereAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = Unit
}

/** The editor popup contract on the real editor popup, with each shape of group a plugin could register. */
class EditorPopupContractTest : BasePlatformTestCase() {
    private val manager get() = ActionManager.getInstance()
    private val editorPopup get() = manager.getAction(IdeActions.GROUP_EDITOR_POPUP) as DefaultActionGroup

    /** Registers [group] holding [child] under the editor popup until the test ends. */
    private fun register(group: DefaultActionGroup, child: AnAction) {
        manager.registerAction(CHILD, child)
        group.add(child)
        manager.registerAction(GROUP, group)
        editorPopup.add(group)
    }

    override fun tearDown() {
        try {
            (manager.getAction(GROUP) as? ActionGroup)?.let(editorPopup::remove)
            manager.unregisterAction(GROUP)
            manager.unregisterAction(CHILD)
            setSqlDialectMapping(project, null, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val contract get() = EditorPopupContract(myFixture, GROUP, listOf(CHILD))

    private fun openSql() {
        myFixture.configureByText("contract-query.sql", "SELECT 1")
        setSqlDialectMapping(project, myFixture.file.virtualFile, GenericDialect.INSTANCE)
        myFixture.configureFromExistingVirtualFile(myFixture.file.virtualFile)
    }

    private fun failure(): String = runCatching { contract.check(::openSql) }.exceptionOrNull().let { failure ->
        assertTrue("$failure", failure is AssertionError)
        failure?.message.orEmpty()
    }

    fun testANonEmptyGroupOfApplicableChildrenShowsOnlyWhereTheyApply() {
        register(NonEmptyActionGroup().apply { isPopup = true }, ContractSqlAction())
        contract.check(::openSql)
    }

    fun testAPlainGroupStaysAsAnEmptySubmenu() {
        register(DefaultActionGroup().apply { isPopup = true }, ContractSqlAction())
        val message = failure()
        assertTrue(message, "popup-notes.txt: $GROUP is in ${IdeActions.GROUP_EDITOR_POPUP}" in message)
        assertFalse(message, "the valid context" in message)
    }

    fun testAChildIgnoringItsContextClutterEveryPopup() {
        register(NonEmptyActionGroup().apply { isPopup = true }, ContractEverywhereAction())
        val message = failure()
        assertTrue(message, "popup-notes.txt: $CHILD is enabled and visible" in message)
        assertTrue(message, "the valid file without an editor: $GROUP is in" in message)
    }

    private companion object {
        const val GROUP = "BrikkContractTest.Group"
        const val CHILD = "BrikkContractTest.Child"
    }
}
