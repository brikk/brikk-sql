package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.Language
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.configureDialect

/** PostgreSQL files analyzed by [pgRelations] over a [FakeFacts] provider. */
abstract class RelationTestCase : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        pgRelations.reset()
        mysqlRelations.reset()
    }

    protected fun open(text: String) = myFixture.configureDialect("PostgreSQL", if ("<caret>" in text) text else "$text<caret>")

    /** Turns on the platform's resolve inspection, whose package differs between releases. */
    protected fun enableResolveInspection() {
        val resolve = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass.endsWith(".SqlResolveInspection") }
        myFixture.enableInspections(resolve.instantiateTool() as LocalInspectionTool)
    }

    /** What the highlighting reports, as the text marked and the message. */
    protected fun reports(severity: HighlightSeverity = HighlightSeverity.WEAK_WARNING): List<Pair<String, String?>> =
        myFixture.doHighlighting(severity).map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) to it.description }

    /** Opens [hostText] as a [host] file with every string literal joined into one injected [injected] fragment. */
    protected fun openInjected(hostText: String, host: String = "GenericSQL", injected: Language = postgres) {
        myFixture.configureDialect(host, "$hostText<caret>")
        val manager = InjectedLanguageManager.getInstance(project)
        manager.registerMultiHostInjector(object : MultiHostInjector {
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                if (manager.isInjectedFragment(context.containingFile)) return
                val literals = PsiTreeUtil.findChildrenOfType(context.containingFile, SqlStringLiteralExpression::class.java).toList()
                if (context != literals.firstOrNull()) return
                registrar.startInjecting(injected)
                for (literal in literals) registrar.addPlace(null, null, literal as PsiLanguageInjectionHost, TextRange(1, literal.textLength - 1))
                registrar.doneInjecting()
            }

            override fun elementsToInjectIn() = listOf(SqlStringLiteralExpression::class.java)
        }, testRootDisposable)
        manager.dropFileCaches(myFixture.file)
    }
}
