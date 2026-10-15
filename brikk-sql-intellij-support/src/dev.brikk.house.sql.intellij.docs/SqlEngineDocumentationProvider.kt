package dev.brikk.house.sql.intellij.docs

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.docs.DocsLink
import dev.brikk.house.sql.docs.DocsKind
import dev.brikk.house.sql.docs.sqlDocsAt
import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.EngineDocs
import org.jetbrains.annotations.ApiStatus

/**
 * Quick documentation and external documentation (Shift+F1) for the SQL constructs the engine documents,
 * from the dialect's [DialectMetadata.docs] (a dialect without them fails here): a statement form, a clause or
 * a table function ([sqlDocsAt]) shows its name, a one-line
 * summary when known and a link to the engine's page for the pinned release, which external documentation
 * opens. Names inside references, such as columns and tables, keep the platform's own documentation; so
 * does everything the engine does not document. Works in injected SQL of the language. Register a no-arg
 * subclass as the language's `lang.documentationProvider` with `order="first"`.
 */
@ApiStatus.Experimental
public abstract class SqlEngineDocumentationProvider(metadata: DialectMetadata) : AbstractDocumentationProvider() {
    private val docs: EngineDocs = requireNotNull(metadata.docs) { "${metadata.dialect} bundles no engine documentation" }

    final override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        val context = contextElement ?: return null
        val reference = PsiTreeUtil.getParentOfType(context, SqlReferenceExpression::class.java, false)
        // A reference's own documentation stays; only a call's name may be a table function.
        if (reference != null && (reference.parent as? SqlFunctionCallExpression)?.nameElement !== reference) return null
        val link = sqlDocsAt(file.text, docs, targetOffset, ProgressManager::checkCanceled) ?: return null
        // A call's name keeps the platform's function documentation unless the call is a table function.
        if (reference != null && link.kind != DocsKind.TABLE_FUNCTION) return null
        return EngineDocsElement(link, context)
    }

    final override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val link = (element as? EngineDocsElement)?.link ?: return null
        val summary = link.summary?.let { DocumentationMarkup.CONTENT_START + StringUtil.escapeXmlEntities(it) + DocumentationMarkup.CONTENT_END }.orEmpty()
        return DocumentationMarkup.DEFINITION_START + StringUtil.escapeXmlEntities(link.title) + DocumentationMarkup.DEFINITION_END + summary +
            DocumentationMarkup.CONTENT_START + "<a href=\"${link.url}\">${StringUtil.escapeXmlEntities(link.url)}</a>" + DocumentationMarkup.CONTENT_END
    }

    final override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): List<String>? =
        (element as? EngineDocsElement)?.link?.url?.let(::listOf)

    final override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? =
        (element as? EngineDocsElement)?.link?.title
}

/** The documented construct at the caret, standing for its engine page. */
internal class EngineDocsElement(val link: DocsLink, private val context: PsiElement) : FakePsiElement() {
    override fun getParent(): PsiElement = context

    override fun getName(): String = link.title

    override fun getPresentableText(): String = link.title

    override fun equals(other: Any?): Boolean = other is EngineDocsElement && other.link == link && other.context == context

    override fun hashCode(): Int = link.hashCode()
}
