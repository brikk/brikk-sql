package dev.brikk.house.sql.intellij.docs

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.lang.documentation.psi.PsiElementDocumentationTarget
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil
import dev.brikk.house.sql.intellij.relation.RelationTestCase
import dev.brikk.house.sql.intellij.relation.postgres
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.TRINO_METADATA

/** A provider reading PostgreSQL files as Doris, so the shared Doris docs map applies. */
class DorisReadDocs : SqlEngineDocumentationProvider(DORIS_METADATA)

class SqlEngineDocumentationProviderTest : RelationTestCase() {
    private val pages = "https://doris.apache.org/docs/4.x/sql-manual/"

    override fun setUp() {
        super.setUp()
        LanguageDocumentation.INSTANCE.addExplicitExtension(postgres, DorisReadDocs(), testRootDisposable)
    }

    /** The documentation target at the caret, as Ctrl+Q and Shift+F1 find it. */
    private fun target(editor: Editor = myFixture.editor, file: PsiFile = myFixture.file): PsiElement? =
        DocumentationManager.getInstance(project).findTargetElement(editor, file)

    private fun url(target: PsiElement?): String? =
        target?.let { DocumentationManager.getProviderFromElement(it).getUrlFor(it, null)?.singleOrNull() }

    fun testAStatementFormShowsItsSummaryAndOpensItsPinnedPage() {
        open("CREATE TABLE t (k INT);\nINSERT IN<caret>TO t SELECT 1")
        val target = target()
        assertEquals("${pages}sql-statements/data-modification/DML/INSERT/", url(target))
        val doc = target?.let { DocumentationManager.getProviderFromElement(it).generateDoc(it, null) }.orEmpty()
        assertTrue(doc, "INSERT" in doc && "Inserts rows" in doc && "${pages}sql-statements/data-modification/DML/INSERT/" in doc)
    }

    fun testATableFunctionInFromOpensItsPage() {
        open("SELECT * FROM num<caret>bers(\"number\" = \"3\") AS n")
        assertEquals("${pages}sql-functions/table-valued-functions/numbers/", url(target()))
    }

    /** The elements of the documentation targets at the caret as Ctrl+Q finds them on 261 and later. */
    private fun ideTargets(): List<PsiElement?> =
        IdeDocumentationTargetProvider.getInstance(project).documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .map { (it as? PsiElementDocumentationTarget)?.targetElement }

    private fun PsiElement?.isEngineDocs() = this?.javaClass?.simpleName == "EngineDocsElement"

    fun testCtrlQFindsTheEnginePage() {
        open("INSERT IN<caret>TO t SELECT 1")
        val target = ideTargets().singleOrNull()
        assertTrue("$target", target.isEngineDocs())
        assertEquals("${pages}sql-statements/data-modification/DML/INSERT/", url(target))
    }

    fun testATableAndAFunctionKeepThePlatformsOwnDocumentation() {
        open("CREATE TABLE orders (k INT);\nSELECT k FROM or<caret>ders")
        val table = target()
        assertNotNull("the platform documents the table", table)
        assertFalse(table.isEngineDocs())
        assertTrue(table?.text.orEmpty(), "orders" in table?.text.orEmpty())
        assertTrue(ideTargets().let { it.isNotEmpty() && it.none { element -> element.isEngineDocs() } })
        open("SELECT ab<caret>s(-1)")
        val function = target()
        assertNotNull("the platform documents the function", function)
        assertFalse(function.isEngineDocs())
        assertTrue(ideTargets().let { it.isNotEmpty() && it.none { element -> element.isEngineDocs() } })
    }

    fun testOtherTokensGetNoEnginePage() {
        open("SELECT k <caret>FROM orders")
        assertFalse(target().isEngineDocs())
        open("SELECT 'CREATE <caret>JOB'")
        assertFalse(target().isEngineDocs())
    }

    /** A provider after this one that documents every element: it answers wherever the engine docs have nothing. */
    class LaterDocs : AbstractDocumentationProvider() {
        override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? = contextElement
    }

    fun testAProviderAfterThisOneStillAnswersOnAReferenceTheEngineDocsLeave() {
        LanguageDocumentation.INSTANCE.addExplicitExtension(postgres, LaterDocs(), testRootDisposable)
        // An unresolved table: the platform has nothing, so the later provider must get it, not an engine page.
        open("SELECT k FROM or<caret>ders")
        assertEquals("orders", target()?.text)
        // Asked first, the engine docs answer where they have a page.
        open("INSERT IN<caret>TO t SELECT 1")
        assertTrue(target().isEngineDocs())
    }

    fun testAPropertyKeyShowsItsSummaryReleaseValuesAndPage() {
        open("CREATE TABLE t (k INT) PROPERTIES ('storage_me<caret>dium' = 'SSD')")
        val target = target()
        assertEquals("${pages}sql-statements/table-and-view/table/CREATE-TABLE/", url(target))
        val doc = target?.let { DocumentationManager.getProviderFromElement(it).generateDoc(it, null) }.orEmpty()
        assertTrue(doc, "storage_medium" in doc && "initial storage medium" in doc && "2.0" in doc && "SSD, HDD" in doc && "CREATE TABLE properties" in doc)
        // A value, and a key no list knows, get no documentation of their own.
        open("CREATE TABLE t (k INT) PROPERTIES ('storage_medium' = 'S<caret>SD')")
        assertFalse(url(target()).orEmpty().contains("CREATE-TABLE"))
        open("CREATE TABLE t (k INT) PROPERTIES ('no_such<caret>_key' = '1')")
        assertFalse(url(target()).orEmpty().contains("CREATE-TABLE"))
    }

    fun testATableKeyInAnAsyncViewShowsTheViewsPageAndItsValues() {
        open("CREATE MATERIALIZED VIEW mv PROPERTIES ('storage_for<caret>mat' = 'V2') AS SELECT 1 AS k")
        val target = target()
        assertEquals("${pages}sql-statements/table-and-view/async-materialized-view/CREATE-ASYNC-MATERIALIZED-VIEW/", url(target))
        val doc = target?.let { DocumentationManager.getProviderFromElement(it).generateDoc(it, null) }.orEmpty()
        assertTrue(doc, "V2, V3, default" in doc && "2.1" in doc && "CREATE MATERIALIZED VIEW properties" in doc)
    }

    fun testATableFunctionsArgumentKeyShowsItsSummary() {
        open("SELECT * FROM s3('for<caret>mat' = 'csv')")
        assertEquals("${pages}sql-functions/table-valued-functions/s3/", url(target()))
    }

    fun testADialectWithoutDocsFailsWhenRegistered() {
        val failure = runCatching { object : SqlEngineDocumentationProvider(TRINO_METADATA) {} }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException && "trino" in failure.message.orEmpty())
    }

    fun testInjectedSqlGetsTheEnginesPages() {
        openInjected("SELECT 'SET exec_mem_limit = 1'", injected = postgres)
        val host = myFixture.editor.document.text
        myFixture.editor.caretModel.moveToOffset(host.indexOf("SET") + 1)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val editor = InjectedLanguageUtil.getEditorForInjectedLanguageNoCommit(myFixture.editor, myFixture.file)
        val file = checkNotNull(PsiDocumentManager.getInstance(project).getPsiFile(editor.document))
        assertEquals(postgres, file.language)
        assertEquals("${pages}sql-statements/session/variable/SET-VARIABLE/", url(target(editor, file)))
    }
}
