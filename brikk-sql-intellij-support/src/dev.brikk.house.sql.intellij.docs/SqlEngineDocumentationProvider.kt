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
import dev.brikk.house.sql.docs.PropertySite
import dev.brikk.house.sql.docs.sqlDocsAt
import dev.brikk.house.sql.docs.sqlPropertyAt
import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.bundledMetadata
import dev.brikk.house.sql.intellij.dialect.SqlDialectRegistry
import org.jetbrains.annotations.ApiStatus

/**
 * Quick documentation and external documentation (Shift+F1) for the SQL constructs the engine documents,
 * from the dialect's [DialectMetadata.docs] (a dialect without them fails here): a statement form, a clause or
 * a table function ([sqlDocsAt]) shows its name, a one-line
 * summary when known and a link to the engine's page for the pinned release, which external documentation
 * opens. Names inside references, such as columns and tables, keep the platform's own documentation; so
 * does everything the engine does not document. On a key of a property list the docs describe
 * (`PROPERTIES ("replication_num" = ...)`, a table function's arguments), it shows the key's summary, the release
 * it came in, its known values and the list's page. Works in injected SQL of the language.
 *
 * Register this class itself as the language's `lang.documentationProvider` with `order="first"`: it finds
 * the docs of the file's language through [SqlDialectRegistry] and [bundledMetadata], so a plugin writes no
 * code. A subclass may pass its metadata instead, which must have docs.
 */
@ApiStatus.Experimental
public open class SqlEngineDocumentationProvider private constructor(private val fixed: Fixed?) : AbstractDocumentationProvider() {
    /** Docs found from each file's language. */
    public constructor() : this(null)

    public constructor(metadata: DialectMetadata) : this(Fixed(metadata))

    /** The metadata a subclass passes, which must have docs. */
    private class Fixed(val metadata: DialectMetadata) {
        init {
            requireNotNull(metadata.docs) { "${metadata.dialect} bundles no engine documentation" }
        }
    }

    /** The metadata for SQL in [file]: this provider's own, or that of the file's dialect. */
    private fun metadataFor(file: PsiFile): DialectMetadata? =
        fixed?.metadata ?: SqlDialectRegistry.forLanguage(file.language)?.coreDialect?.let(::bundledMetadata)

    final override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        val context = contextElement ?: return null
        val metadata = metadataFor(file) ?: return null
        val docs = metadata.docs ?: return null
        // A key of a property list the docs describe: its summary, release, values and the list's page.
        sqlPropertyAt(file.text, docs, targetOffset, metadata.default::tableFunction, ProgressManager::checkCanceled)
            ?.takeIf { !it.value && it.quoted && it.definition != null }?.let { return PropertyDocsElement(it, context) }
        val reference = PsiTreeUtil.getParentOfType(context, SqlReferenceExpression::class.java, false)
        // A reference's own documentation stays; only a call's name may be a table function.
        if (reference != null && (reference.parent as? SqlFunctionCallExpression)?.nameElement !== reference) return null
        val link = sqlDocsAt(file.text, docs, targetOffset, ProgressManager::checkCanceled) ?: return null
        // A call's name keeps the platform's function documentation unless the call is a table function.
        if (reference != null && link.kind != DocsKind.TABLE_FUNCTION) return null
        return EngineDocsElement(link, context)
    }

    final override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element is PropertyDocsElement) return propertyDoc(element.site)
        val link = (element as? EngineDocsElement)?.link ?: return null
        val summary = link.summary?.let { DocumentationMarkup.CONTENT_START + StringUtil.escapeXmlEntities(it) + DocumentationMarkup.CONTENT_END }.orEmpty()
        return DocumentationMarkup.DEFINITION_START + StringUtil.escapeXmlEntities(link.title) + DocumentationMarkup.DEFINITION_END + summary +
            DocumentationMarkup.CONTENT_START + "<a href=\"${link.url}\">${StringUtil.escapeXmlEntities(link.url)}</a>" + DocumentationMarkup.CONTENT_END
    }

    final override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): List<String>? = when (element) {
        is EngineDocsElement -> listOf(element.link.url)
        is PropertyDocsElement -> element.site.url?.let(::listOf)
        else -> null
    }

    final override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? = when (element) {
        is EngineDocsElement -> element.link.title
        is PropertyDocsElement -> element.name
        else -> null
    }

    private fun propertyDoc(site: PropertySite): String {
        val definition = site.definition ?: return ""
        fun content(text: String) = DocumentationMarkup.CONTENT_START + text + DocumentationMarkup.CONTENT_END
        val sections = buildList {
            add(DocumentationMarkup.SECTION_HEADER_START + "Of:" + DocumentationMarkup.SECTION_SEPARATOR + StringUtil.escapeXmlEntities(site.title) + DocumentationMarkup.SECTION_END)
            definition.since?.let { add(DocumentationMarkup.SECTION_HEADER_START + "Since:" + DocumentationMarkup.SECTION_SEPARATOR + StringUtil.escapeXmlEntities(it) + DocumentationMarkup.SECTION_END) }
            if (definition.values.isNotEmpty()) {
                add(DocumentationMarkup.SECTION_HEADER_START + "Values:" + DocumentationMarkup.SECTION_SEPARATOR +
                    StringUtil.escapeXmlEntities(definition.values.joinToString(", ")) + DocumentationMarkup.SECTION_END)
            }
        }
        return DocumentationMarkup.DEFINITION_START + StringUtil.escapeXmlEntities(definition.name) + DocumentationMarkup.DEFINITION_END +
            definition.summary?.let { content(StringUtil.escapeXmlEntities(it)) }.orEmpty() +
            DocumentationMarkup.SECTIONS_START + sections.joinToString("") + DocumentationMarkup.SECTIONS_END +
            site.url?.let { content("<a href=\"$it\">${StringUtil.escapeXmlEntities(it)}</a>") }.orEmpty()
    }
}

/** A key of a property list at the caret, standing for its documentation. */
internal class PropertyDocsElement(val site: PropertySite, private val context: PsiElement) : FakePsiElement() {
    override fun getParent(): PsiElement = context

    override fun getName(): String = site.definition?.name ?: site.key.orEmpty()

    override fun getPresentableText(): String = name

    override fun equals(other: Any?): Boolean = other is PropertyDocsElement && other.site == site && other.context == context

    override fun hashCode(): Int = site.hashCode()
}

/** The documented construct at the caret, standing for its engine page. */
internal class EngineDocsElement(val link: DocsLink, private val context: PsiElement) : FakePsiElement() {
    override fun getParent(): PsiElement = context

    override fun getName(): String = link.title

    override fun getPresentableText(): String = link.title

    override fun equals(other: Any?): Boolean = other is EngineDocsElement && other.link == link && other.context == context

    override fun hashCode(): Int = link.hashCode()
}
