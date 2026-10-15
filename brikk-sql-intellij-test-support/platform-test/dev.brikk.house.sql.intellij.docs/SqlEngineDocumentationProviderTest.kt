package dev.brikk.house.sql.intellij.docs

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.LanguageDocumentation
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

    fun testReferencesKeepTheirOwnDocumentationAndOtherTokensGetNone() {
        open("SELECT k FROM or<caret>ders")
        assertFalse(url(target()).orEmpty().startsWith(pages))
        open("SELECT k <caret>FROM orders")
        assertFalse(url(target()).orEmpty().startsWith(pages))
        open("SELECT 'CREATE <caret>JOB'")
        assertFalse(url(target()).orEmpty().startsWith(pages))
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
