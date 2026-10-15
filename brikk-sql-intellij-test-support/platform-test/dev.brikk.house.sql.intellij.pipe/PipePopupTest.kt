package dev.brikk.house.sql.intellij.pipe

import com.intellij.ide.actions.NonEmptyActionGroup
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.EditorPopupContract
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import java.util.UUID

/** PIPE of PostgreSQL files only, so another dialect's file is a real control. */
private val postgresPipe = object : PipeDialect by testPipeDialects.first() {
    override val language: Language get() = checkNotNull(Language.findLanguageByID("PostgreSQL"))
}

class PopupPipePreview : PipePreviewAction(postgresPipe)
class PopupPipeRunToStage : PipeRunToStageAction(postgresPipe)

/** The shared PIPE editor actions in a plugin's editor popup group, as the platform renders the popup. */
class PipePopupTest : BasePlatformTestCase() {
    private val manager get() = ActionManager.getInstance()
    private val editorPopup get() = manager.getAction(IdeActions.GROUP_EDITOR_POPUP) as DefaultActionGroup
    private val trino get() = testPipeDialects.first() as TestPipeDialect

    override fun setUp() {
        super.setUp()
        val group = NonEmptyActionGroup().apply { isPopup = true }
        for ((id, action) in listOf(PREVIEW to PopupPipePreview(), RUN to PopupPipeRunToStage())) {
            manager.registerAction(id, action)
            group.add(action)
        }
        manager.registerAction(GROUP, group)
        editorPopup.add(group)
    }

    override fun tearDown() {
        try {
            manager.getAction(GROUP)?.let(editorPopup::remove)
            listOf(GROUP, PREVIEW, RUN).forEach(manager::unregisterAction)
            trino.enabled = true
            setSqlDialectMapping(project, null, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val contract get() = EditorPopupContract(myFixture, GROUP, listOf(PREVIEW, RUN))

    private fun open(text: String, dialect: String = "PostgreSQL") {
        val file = myFixture.configureByText("pipe-popup-${UUID.randomUUID()}.sql", text.replace("<caret>", ""))
        setSqlDialectMapping(project, file.virtualFile, Language.findLanguageByID(dialect) as SqlLanguageDialect, testRootDisposable)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
    }

    fun testThePipeEntriesShowOnlyInTheirOwnEditorsPipeStatement() {
        contract.check { open("FROM t |> WHERE a = 1<caret>") }
    }

    fun testAnotherDialectNoPipeOrPipeTurnedOffShowNothing() {
        open("FROM t |> WHERE a = 1<caret>", dialect = "MySQL")
        contract.assertAbsent(what = "PIPE in another dialect's file")
        open("SELECT 1<caret>")
        contract.assertAbsent(what = "a statement without a pipe")
        open("FROM t |> WHERE a = 1<caret>")
        trino.enabled = false
        contract.assertAbsent(what = "PIPE turned off for the project")
    }

    private companion object {
        const val GROUP = "BrikkPipeTest.Group"
        const val PREVIEW = "BrikkPipeTest.Preview"
        const val RUN = "BrikkPipeTest.RunToStage"
    }
}
