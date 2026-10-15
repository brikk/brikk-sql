package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.TreePattern
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.completion.configureDialect
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.EditorPopupContract
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Where the refresh action shows, with no server: in the editor popup only for SQL a data source of the dbms
 * serves (the engine's own SQL, or SQL explicitly bound to one), never for other files, missing context or
 * another engine's SQL, though the project's only data source is of the dbms; elsewhere it keeps finding that
 * data source.
 */
class RefreshLiveCatalogActionTest : BasePlatformTestCase() {
    class Refresh : RefreshLiveCatalogAction(LiveService::class.java)

    private lateinit var errors: BackgroundErrors
    private lateinit var only: LocalDataSource
    private val added = mutableListOf<LocalDataSource>()
    private val mapped = mutableListOf<PsiFile>()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        val service = LiveService(errors.newScope(testRootDisposable), LiveProfile(dbms = Dbms.POSTGRES), null)
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(LiveService::class.java, service, testRootDisposable)
        only = dataSource()
    }

    override fun tearDown() {
        try {
            mapped.forEach { SqlResolveMappings.getInstance(project).setMapping(it.virtualFile, null) }
            added.forEach(LocalDataSourceManager.getInstance(project)::removeDataSource)
            DbPsiFacade.getInstance(project).clearCaches()
            errors.awaitScopes()
            errors.assertNone()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun dataSource(): LocalDataSource {
        val dataSource = LocalDataSource().apply {
            name = "refresh ${UUID.randomUUID()}"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("postgresql"))
            url = "jdbc:offline:refresh"
            isAutoSynchronize = false
        }
        LocalDataSourceManager.getInstance(project).addDataSource(dataSource)
        DbPsiFacade.getInstance(project).clearCaches()
        added += dataSource
        assertEquals(Dbms.POSTGRES, dataSource.dbms)
        return dataSource
    }

    /** Opens [text] as `name`; a SQL file in [dialect] (a language id), `<caret>` placing the caret. */
    private fun open(name: String, text: String, dialect: String? = null): PsiFile {
        val file = myFixture.configureByText("refresh-${UUID.randomUUID()}-$name", text)
        if (dialect != null) {
            setSqlDialectMapping(project, file.virtualFile, Language.findLanguageByID(dialect) as SqlLanguageDialect, testRootDisposable)
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
        }
        return myFixture.file
    }

    /** The action's presentation at [place] for [editor] and [file], updated as the IDE does: on a background thread, in a read action. */
    private fun presentation(place: String, editor: Editor? = myFixture.editor, file: PsiFile? = myFixture.file): Presentation {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).apply {
            if (editor != null) add(CommonDataKeys.EDITOR, editor)
            if (file != null) add(CommonDataKeys.PSI_FILE, file)
        }.build()
        val presentation = Presentation()
        val event = AnActionEvent.createFromDataContext(place, presentation, context)
        ApplicationManager.getApplication().executeOnPooledThread { ReadAction.run<Throwable> { Refresh().update(event) } }.get(30, TimeUnit.SECONDS)
        return presentation
    }

    private fun assertInPopup(shown: Boolean, what: String, editor: Editor? = myFixture.editor, file: PsiFile? = myFixture.file) {
        val presentation = presentation(ActionPlaces.EDITOR_POPUP, editor, file)
        assertEquals(what, shown, presentation.isVisible)
        assertEquals(what, shown, presentation.isEnabled)
    }

    fun testUnrelatedFilesAndMissingContextShowNothingThoughOneDataSourceServesTheDbms() {
        open("notes.txt", "SELECT 1<caret>")
        assertInPopup(false, "a text file")
        open("Plain.java", "class Plain {<caret>}")
        assertInPopup(false, "a Java file")
        assertInPopup(false, "no editor or file", editor = null, file = null)
    }

    fun testTheEnginesSqlNeedsItsOwnEditor() {
        val sql = open("query.sql", "SELECT 1<caret>", dialect = "PostgreSQL")
        assertInPopup(false, "the engine's SQL without an editor", editor = null)
        open("notes.txt", "SELECT 1<caret>")
        assertInPopup(false, "the engine's SQL with a text file's editor", file = sql)
        open("other.sql", "SELECT 2<caret>", dialect = "PostgreSQL")
        // Mapping the other file's dialect reparsed the first: its current PSI, which an invalid one would fall back from to the editor's.
        val current = checkNotNull(PsiManager.getInstance(project).findFile(sql.virtualFile))
        assertInPopup(false, "the engine's SQL with another SQL file's editor", file = current)
    }

    fun testTheEditorPopupContract() {
        val action = Refresh()
        ActionManager.getInstance().registerAction(REFRESH, action)
        val popup = ActionManager.getInstance().getAction(IdeActions.GROUP_EDITOR_POPUP) as DefaultActionGroup
        popup.add(action)
        try {
            EditorPopupContract(myFixture, REFRESH).check { open("query.sql", "SELECT 1<caret>", dialect = "PostgreSQL") }
        } finally {
            popup.remove(action)
            ActionManager.getInstance().unregisterAction(REFRESH)
        }
    }

    fun testTheEnginesOwnSqlShowsItAndAnotherEnginesDoesNot() {
        open("query.sql", "SELECT 1<caret>", dialect = "PostgreSQL")
        assertInPopup(true, "the engine's own SQL, served by the only data source")
        open("query.sql", "SELECT 1<caret>", dialect = "MySQL")
        assertInPopup(false, "another engine's SQL")
    }

    fun testGenericSqlShowsItOnlyWhenExplicitlyBound() {
        val file = open("query.sql", "SELECT 1<caret>", dialect = "GenericSQL")
        assertInPopup(false, "generic SQL bound to nothing")
        mapped += file
        SqlResolveMappings.getInstance(project).setMapping(file.virtualFile, TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(only.uniqueId)))))
        assertInPopup(true, "generic SQL whose resolution scope names the data source")
        SqlResolveMappings.getInstance(project).setMapping(file.virtualFile, null)
        RecordingConsole(project, file.virtualFile, only).use {
            assertInPopup(true, "generic SQL in a console of the data source")
        }
    }

    fun testSqlInjectedInGenericSqlCountsAsItsOwnDialect() {
        myFixture.configureDialect("GenericSQL", "SELECT 'SELECT <caret>1'")
        val host = myFixture.file
        val postgres = checkNotNull(Language.findLanguageByID("PostgreSQL"))
        val manager = InjectedLanguageManager.getInstance(project)
        manager.registerMultiHostInjector(object : MultiHostInjector {
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                if (manager.isInjectedFragment(context.containingFile)) return
                val literal = context as SqlStringLiteralExpression
                registrar.startInjecting(postgres).addPlace(null, null, literal as PsiLanguageInjectionHost, TextRange(1, literal.textLength - 1)).doneInjecting()
            }

            override fun elementsToInjectIn() = listOf(SqlStringLiteralExpression::class.java)
        }, testRootDisposable)
        manager.dropFileCaches(host)
        val injected = manager.findInjectedElementAt(myFixture.file, myFixture.caretOffset)?.containingFile
        assertEquals("the fixture injects PostgreSQL at the caret", postgres, injected?.language)
        assertInPopup(true, "PostgreSQL injected at the caret")
        val fragment = checkNotNull(injected)
        val injectedEditor = InjectedLanguageUtil.getEditorForInjectedLanguageNoCommit(myFixture.editor, host, myFixture.caretOffset)
        assertInPopup(true, "the injected editor with its injected SQL", editor = injectedEditor, file = fragment)
        assertInPopup(false, "the host editor with the injected SQL", file = fragment)
        assertInPopup(false, "the injected editor with the host", editor = injectedEditor, file = host)
        myFixture.editor.caretModel.moveToOffset(0)
        assertInPopup(false, "the generic host outside the injection")
    }

    fun testElsewhereTheProjectsOnlyDataSourceStillServes() {
        val searched = presentation(ActionPlaces.ACTION_SEARCH, editor = null, file = null)
        assertTrue(searched.isVisible)
        assertTrue("Find Action refreshes the project's only data source", searched.isEnabled)
        open("notes.txt", "SELECT 1<caret>")
        val menu = presentation(ActionPlaces.MAIN_MENU)
        assertTrue(menu.isVisible)
        assertTrue(menu.isEnabled)
    }

    private companion object {
        const val REFRESH = "BrikkTest.RefreshLiveCatalog"
    }
}
