package dev.brikk.house.sql.intellij.docs

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.intellij.dialect.DatafusionEditorProfile
import dev.brikk.house.sql.metadata.*
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import dev.brikk.house.sql.intellij.pipe.PipeText

/** Function evidence plus the shared engine construct documentation, selected from cached per-file metadata. */
public open class DatafusionDocumentationProvider(private val profile: DatafusionEditorProfile) : AbstractDocumentationProvider() {
    private val constructs = SqlEngineDocumentationProvider(profile.metadata)

    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        if (file.language != profile.language || contextElement == null) return null
        val source = PipeText.of(file)
        val offset = source.textOffset(targetOffset)
        val tokens = Tokenizer(TokenizerConfigs.forName(profile.dialect)).tokenizeForEditor(source.text, ProgressManager::checkCanceled).tokens
        val index = tokens.indexOfFirst { offset in it.start..it.end }
        val token = tokens.getOrNull(index)
        if (token?.tokenType == TokenType.STRING || token?.tokenType?.name?.endsWith("_STRING") == true) return null
        if (token != null && token.tokenType !in setOf(TokenType.STRING, TokenType.IDENTIFIER) && tokens.getOrNull(index + 1)?.tokenType == TokenType.L_PAREN && tokens.getOrNull(index - 1)?.tokenType != TokenType.DOT) {
            val reference = PsiTreeUtil.getParentOfType(contextElement, SqlReferenceExpression::class.java, false)
            val resolved = reference?.resolveSymbol()
            // A local or connection-owned routine keeps its own docs, even if it shadows a builtin.
            if (resolved != null && resolved !is BuiltinFunction) return null
            val table = (reference?.parent as? SqlFunctionCallExpression)?.parent?.node?.elementType == com.intellij.sql.psi.SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION ||
                profile.tableCall(file, source.fileRange(token.start, token.end + 1)?.startOffset ?: targetOffset)
            val entries = profile.functions(file).filter { it.name.equals(token.text, true) }
            val entry = entries.firstOrNull { (it.kind == FunctionKind.TABLE_VALUED) == table } ?: entries.firstOrNull()
            if (entry != null) return FunctionDocs(entry, file, contextElement)
        }
        return constructs.getCustomDocumentationElement(editor, file, contextElement, targetOffset)
    }

    override fun getDocumentationElementForLookupItem(psiManager: PsiManager, obj: Any?, element: PsiElement?): PsiElement? {
        val file = element?.containingFile ?: return null
        if (file.language != profile.language || obj !is FunctionPresentation) return null
        return FunctionDocs(obj, file, element)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val item = element as? FunctionDocs ?: return constructs.generateDoc(element, originalElement)
        val entry = item.entry
        val snapshot = profile.snapshot(item.file)
        val evidence = if (profile.dialect == "spice") SPICE_PROFILE_EVIDENCE else DATAFUSION_PROFILE_EVIDENCE
        val pinned = snapshot.engineVersion == if (profile.dialect == "spice") "2.4.0" else "55.2.0"
        val captured = if (pinned) evidence.functions(entry.name).filter { it.kind == entry.kind } else emptyList()
        fun esc(text: String) = StringUtil.escapeXmlEntities(text)
        val definitions = snapshot.functions.getAll(entry.name).filter { it.kind == entry.kind }
        val signatures = definitions.flatMap { it.overloads }.map { "${entry.name}(${it.argTypes.joinToString(", ")}) → ${it.returnType}" }
        val parts = mutableListOf("<pre>${esc(entry.name)} · ${esc(entry.kind.name.lowercase())}</pre>",
            "<p>Source: ${esc(profile.catalogProvenance(item.file))}.</p>",
            "<p>Inventory: ${esc(profile.dialect)} ${esc(snapshot.engineVersion)}. Server version: ${esc(profile.serverVersion(item.file) ?: "unknown; bundled fallback")}.</p>")
        parts += if (signatures.isEmpty()) "<p>Signature unknown. Introspection examples are not exhaustive overloads.</p>" else "<pre>${esc(signatures.joinToString("\n"))}</pre>"
        if (signatures.isEmpty()) entry.returnType?.let { parts += "<p>Return type: ${esc(it)}. This does not establish the accepted argument signatures.</p>" }
        captured.mapNotNull { it.canonicalName }.distinct().filter { it != entry.name }.forEach { parts += "<p>Alias of ${esc(it)}.</p>" }
        captured.flatMap { it.descriptions }.distinct().forEach { parts += "<p>${esc(it)}</p>" }
        captured.flatMap { it.syntaxExamples }.distinct().forEach { parts += "<pre>${esc(it)}</pre>" }
        if (entry.namedParameters.isNotEmpty()) parts += "<p>Named arguments: ${esc(entry.namedParameters.joinToString(", ") { "$it => …" })}</p>"
        if (entry.name.startsWith("ai_") || entry.name in setOf("ai", "embed")) parts += "<p>Requires model configuration. Analysis does not invoke a model.</p>"
        if (entry.name in setOf("vector_search", "text_search", "rrf", "rerank")) parts += "<p>Output depends on input datasets and configuration. Nested registrations are scalar syntax stubs, not executable scalar functions.</p>"
        parts += if (pinned) "<p>${esc(evidence.availability)}</p>" else "<p>No bundled source evidence for this snapshot.</p>"
        val url = url(entry)
        parts += "<p><a href=\"${esc(url)}\">Upstream documentation</a> (current page; inventory above is version-pinned).</p>"
        return parts.joinToString("\n")
    }

    override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): List<String>? =
        (element as? FunctionDocs)?.let { listOf(url(it.entry)) } ?: constructs.getUrlFor(element, originalElement)

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? =
        (element as? FunctionDocs)?.entry?.name ?: constructs.getQuickNavigateInfo(element, originalElement)

    private fun url(entry: FunctionPresentation): String {
        val docs = requireNotNull(profile.metadata.docs)
        val page = docs.tableFunctions[entry.name] ?: when {
            profile.dialect == "spice" && entry.name.startsWith("json_") -> "json"
            profile.dialect == "spice" && (entry.name.startsWith("ai_") || entry.name in setOf("ai", "embed")) -> "ai"
            entry.kind == FunctionKind.AGGREGATE -> if (profile.dialect == "spice") "aggregate_functions" else "aggregate_functions.html"
            entry.kind == FunctionKind.WINDOW -> if (profile.dialect == "spice") "window_functions" else "window_functions.html"
            entry.kind == FunctionKind.TABLE_VALUED -> ""
            else -> if (profile.dialect == "spice") "scalar_functions" else "scalar_functions.html"
        }
        return docs.base + page
    }

    private class FunctionDocs(val entry: FunctionPresentation, val file: PsiFile, private val context: PsiElement) : FakePsiElement() {
        override fun getParent(): PsiElement = context
        override fun getName(): String = entry.name
        override fun getPresentableText(): String = name
    }
}
