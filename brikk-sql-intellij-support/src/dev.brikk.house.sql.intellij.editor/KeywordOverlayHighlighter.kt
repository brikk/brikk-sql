package dev.brikk.house.sql.intellij.editor

import com.intellij.lang.Language
import com.intellij.lexer.DelegateLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.psi.SqlTokens
import java.util.concurrent.ConcurrentHashMap

/**
 * Wraps the SQL highlighter of [language] and colors plain identifiers whose text is one of the
 * upper-case [keywords] as keywords. Lexical, so a keyword used as a name is colored too. Every
 * other token keeps the wrapped highlighter's colors. Plugins register it through
 * [KeywordOverlayHighlighterFactory].
 */
public class KeywordOverlayHighlighter(
    private val base: SyntaxHighlighter,
    language: Language,
    private val keywords: Set<String>,
) : SyntaxHighlighterBase() {
    private val keyword = overlayTypes.computeIfAbsent(language) { IElementType("${it.id}_OVERLAY_KEYWORD", it) }

    override fun getHighlightingLexer(): Lexer = object : DelegateLexer(base.highlightingLexer) {
        override fun getTokenType(): IElementType? = super.getTokenType()?.let { type ->
            if (type == SqlTokens.SQL_IDENT && tokenText.uppercase() in keywords) keyword else type
        }
    }

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        base.getTokenHighlights(if (tokenType == keyword) SqlTokens.SQL_KEYWORD_TOKEN else tokenType)
}

/** Element types register globally and permanently, so each language gets one. */
private val overlayTypes = ConcurrentHashMap<Language, IElementType>()

/**
 * The SQL highlighter factory of [dialect] with a [KeywordOverlayHighlighter] over [keywords].
 * Register a no-arg subclass as the dialect's `lang.syntaxHighlighterFactory`.
 */
public abstract class KeywordOverlayHighlighterFactory(
    private val dialect: SqlLanguageDialect,
    private val keywords: Set<String>,
) : SqlSyntaxHighlighterFactory.Base(dialect) {
    final override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        KeywordOverlayHighlighter(super.getSyntaxHighlighter(project, virtualFile), dialect, keywords)
}
