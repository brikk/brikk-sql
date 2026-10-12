package dev.brikk.house.sql.intellij.pipe

import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStatement
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.UUID

/** PIPE SQL injected into SQL string literals; `''` escapes a quote, as in Kotlin or Java string escapes. */
class PipeTextTest : BasePlatformTestCase() {
    private val dialect = testPipeDialects.first()

    /** Injects the string literals of the host statement as one file, separated by a non-editable space. */
    private fun inject(host: String): PsiFile {
        myFixture.configureByText("host-${UUID.randomUUID()}.sql", host)
        val language = myFixture.file.language
        val injection = InjectedLanguageManager.getInstance(project)
        injection.registerMultiHostInjector(object : MultiHostInjector {
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                val statement = PsiTreeUtil.getParentOfType(context, SqlStatement::class.java) ?: return
                val literals = PsiTreeUtil.findChildrenOfType(statement, SqlStringLiteralExpression::class.java)
                if (literals.firstOrNull() !== context) return
                registrar.startInjecting(language)
                for ((index, literal) in literals.withIndex()) {
                    val inner = TextRange(1, literal.textLength - 1)
                    registrar.addPlace(null, if (index < literals.size - 1) " " else null, literal, inner)
                }
                registrar.doneInjecting()
            }

            override fun elementsToInjectIn() = listOf(SqlStringLiteralExpression::class.java)
        }, testRootDisposable)
        val offset = host.indexOf('\'') + 1
        return checkNotNull(injection.findInjectedElementAt(myFixture.file, offset)?.containingFile) { "Nothing injected" }
    }

    private fun PsiFile.hostText(range: TextRange): String =
        myFixture.file.text.substring(InjectedLanguageManager.getInstance(project).injectedToHost(this, range).let { it.startOffset until it.endOffset })

    fun testInjectedTextIsUnescapedAndOffsetsMapBothWays() {
        val host = "SELECT 'FROM t |> WHERE a = ''x'' |> FROBNICATE y'"
        val injected = inject(host)
        val source = dialect.pipeText(injected)
        assertEquals("FROM t |> WHERE a = 'x' |> FROBNICATE y", source.text)

        val word = source.text.indexOf("FROBNICATE")
        val window = PsiDocumentManager.getInstance(project).getDocument(injected) as DocumentWindow
        assertEquals(word, source.textOffset(window.hostToInjected(host.indexOf("FROBNICATE"))))

        val refused = dialect.lower(source.text) as PipeTranslation.Refused
        val range = checkNotNull(source.errorRange(0, source.text, refused.range))
        assertEquals("FROBNICATE", injected.hostText(range))
    }

    fun testARangeAcrossFragmentsIsCutToItsFirstEditablePart() {
        val host = "SELECT 'FROM t |> WHERE' || 'a = 1'"
        val injected = inject(host)
        val source = dialect.pipeText(injected)
        assertEquals("FROM t |> WHERE a = 1", source.text)
        val range = checkNotNull(source.fileRange(source.text.indexOf("WHERE"), source.text.indexOf("= 1")))
        assertEquals("WHERE", injected.hostText(range))
    }
}
