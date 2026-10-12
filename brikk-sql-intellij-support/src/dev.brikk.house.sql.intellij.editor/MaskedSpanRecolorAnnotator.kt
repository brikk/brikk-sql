package dev.brikk.house.sql.intellij.editor

import com.intellij.lang.Language
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlCommonTokens
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import org.jetbrains.annotations.ApiStatus

/**
 * Recolors the spans a masking lexer turned into one comment, string or identifier token, so they
 * read as the SQL they are. A comment leaf without a comment introducer, a string leaf without a
 * leading quote, and an identifier leaf the core tokenizer of [coreDialect] reads as several
 * tokens are such spans. Each core token gets the colors the file's highlighter gives the platform
 * token of the same kind; comments inside a span keep comment colors.
 *
 * Words are keywords when the core tokenizer reads them as such, or, with [keywords], when they
 * are in that upper-case set. Register a no-arg subclass for the dialect's language.
 */
@ApiStatus.Experimental
public abstract class MaskedSpanRecolorAnnotator(
    private val language: Language,
    private val coreDialect: String,
    private val keywords: Set<String>? = null,
) : Annotator {
    private val config by lazy { TokenizerConfigs.forName(coreDialect) }

    final override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return
        val type = element.node?.elementType ?: return
        if (element !is PsiComment && type != SqlTokens.SQL_STRING_TOKEN && type != SqlTokens.SQL_IDENT) return
        val text = element.text
        if (text.isEmpty() || !isSpan(element, type, text)) return
        val file = element.containingFile ?: return
        if (!file.language.isKindOf(language)) return
        val lexed = Tokenizer(config).tokenizeForEditor(text)
        if (type == SqlTokens.SQL_IDENT && lexed.tokens.size < 2) return
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile) ?: return
        val base = element.textRange.startOffset
        var covered = 0
        for (token in lexed.tokens) {
            comments(text, covered, token.start, highlighter, base, holder)
            recolor(holder, base + token.start, base + token.end + 1, attributes(highlighter, platformType(token)))
            covered = token.end + 1
        }
        comments(text, covered, lexed.failureOffset ?: text.length, highlighter, base, holder)
    }

    private fun isSpan(element: PsiElement, type: IElementType, text: String): Boolean = when {
        element is PsiComment -> config.comments.keys.none(text::startsWith)
        type == SqlTokens.SQL_STRING_TOKEN -> !startsAsString(text)
        type == SqlTokens.SQL_IDENT -> !text.all { it.isLetterOrDigit() || it == '_' || it == '$' }
        else -> false
    }

    /** Non-blank text between tokens is a comment. */
    private fun comments(text: String, from: Int, to: Int, highlighter: SyntaxHighlighter, base: Int, holder: AnnotationHolder) {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start < end) recolor(holder, base + start, base + end, attributes(highlighter, SqlTokens.SQL_BLOCK_COMMENT))
    }

    /** The platform token of the same kind; null for operators, which the highlighter leaves plain. */
    private fun platformType(token: Token): IElementType? = when (token.tokenType) {
        TokenType.NUMBER -> SqlTokens.SQL_INTEGER_TOKEN
        TokenType.IDENTIFIER -> SqlTokens.SQL_IDENT_DELIMITED
        TokenType.VAR -> if (keywords?.contains(token.text.uppercase()) == true) SqlTokens.SQL_KEYWORD_TOKEN else SqlTokens.SQL_IDENT
        TokenType.L_PAREN, TokenType.R_PAREN -> SqlTokens.SQL_LEFT_PAREN
        TokenType.L_BRACKET, TokenType.R_BRACKET -> SqlTokens.SQL_LEFT_BRACKET
        TokenType.L_BRACE, TokenType.R_BRACE -> SqlTokens.SQL_LEFT_BRACE
        TokenType.COMMA -> SqlCommonTokens.SQL_COMMA
        TokenType.DOT -> SqlTokens.SQL_PERIOD
        else -> when {
            token.tokenType == TokenType.STRING || token.tokenType.name.endsWith("_STRING") -> SqlTokens.SQL_STRING_TOKEN
            token.text.firstOrNull()?.isLetter() != true -> null
            keywords == null || token.text.uppercase() in keywords -> SqlTokens.SQL_KEYWORD_TOKEN
            else -> SqlTokens.SQL_IDENT
        }
    }

    private fun attributes(highlighter: SyntaxHighlighter, type: IElementType?): TextAttributesKey =
        type?.let { highlighter.getTokenHighlights(it).lastOrNull() } ?: HighlighterColors.TEXT

    private fun recolor(holder: AnnotationHolder, start: Int, end: Int, key: TextAttributesKey) {
        if (end > start) holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(TextRange(start, end)).textAttributes(key).create()
    }
}

/** A string token starts with a quote, optionally after a prefix of up to two letters or `&`, such as `E'` or `U&'`. */
private fun startsAsString(text: String): Boolean {
    val prefix = text.indexOfFirst { !it.isLetter() && it != '&' }
    return prefix in 0..2 && text[prefix] in "'\"`$"
}
