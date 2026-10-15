package dev.brikk.house.sql.intellij.testing

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.lang.documentation.psi.PsiElementDocumentationTarget
import com.intellij.openapi.Disposable
import com.intellij.psi.PsiElement
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.jetbrains.annotations.ApiStatus
import java.net.URI
import java.util.UUID

/**
 * The engine documentation a plugin registers for its [language] (`SqlEngineDocumentationProvider`), checked
 * the way Ctrl+Q and Shift+F1 reach it, through `DocumentationManager` and through
 * `IdeDocumentationTargetProvider` (what Ctrl+Q uses on 261 and later). Run [check] from a light platform test
 * of the plugin; every problem is reported together.
 */
@ApiStatus.Experimental
public class DocumentationProviderContract(
    private val fixture: CodeInsightTestFixture,
    private val language: SqlLanguageDialect,
    private val until: Disposable,
) {
    /**
     * [statement], with `<caret>` on a construct the engine documents, links a page on [docsHost]; a reference to
     * the table [table] (created in the file) keeps the platform's own documentation element, and the function
     * [function] (called in a SELECT) links no engine page.
     */
    public fun check(statement: String, docsHost: String, table: String, function: String = "abs") {
        val failures = ArrayList<String>()
        open(statement)
        for ((path, element) in targets()) {
            val host = url(element)?.let { runCatching { URI(it).host }.getOrNull() }
            if (host != docsHost) failures += "$path: $statement links ${url(element)}, not a page on $docsHost"
        }
        open("CREATE TABLE $table (k INT);\nSELECT k FROM ${table.take(1)}<caret>${table.drop(1)}")
        for ((path, element) in targets()) {
            if (element == null) failures += "$path: the table reference $table has no documentation element"
            else if (element.isEngineDocs() || url(element)?.contains(docsHost) == true) failures += "$path: the table reference $table gets the engine docs"
        }
        open("SELECT ${function.take(1)}<caret>${function.drop(1)}(1)")
        for ((path, element) in targets()) {
            if (element.isEngineDocs() || url(element)?.contains(docsHost) == true) failures += "$path: the function $function gets the engine docs"
        }
        if (failures.isNotEmpty()) throw AssertionError("${failures.size} documentation problem(s) in ${language.id}:\n" + failures.joinToString("\n"))
    }

    private fun open(sql: String) {
        val file = fixture.configureByText("docs-${UUID.randomUUID()}.sql", sql).virtualFile
        setSqlDialectMapping(fixture.project, file, language, until)
        fixture.configureFromExistingVirtualFile(file)
    }

    /** The documentation element at the caret through each path the IDE takes. */
    private fun targets(): List<Pair<String, PsiElement?>> {
        val manager = DocumentationManager.getInstance(fixture.project).findTargetElement(fixture.editor, fixture.file)
        val ide = IdeDocumentationTargetProvider.getInstance(fixture.project).documentationTargets(fixture.editor, fixture.file, fixture.caretOffset)
            .map { (it as? PsiElementDocumentationTarget)?.targetElement }
        return listOf("DocumentationManager" to manager) + (ide.ifEmpty { listOf(null) }).map { "IdeDocumentationTargetProvider" to it }
    }

    private fun url(element: PsiElement?): String? =
        element?.let { DocumentationManager.getProviderFromElement(it).getUrlFor(it, null)?.firstOrNull() }

    private fun PsiElement?.isEngineDocs(): Boolean = this?.javaClass?.simpleName == "EngineDocsElement"
}
