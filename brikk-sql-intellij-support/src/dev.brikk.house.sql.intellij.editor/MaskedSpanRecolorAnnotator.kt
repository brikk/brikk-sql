package dev.brikk.house.sql.intellij.editor

import com.intellij.lang.Language
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
 * token of the same kind; comments inside a span keep comment colors. In injected SQL a span is
 * read decoded, as the host's escaper decodes it.
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
    private val config = TokenizerConfigs.forName(coreDialect)

    final override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return
        val type = element.node?.elementType ?: return
        val comment = element is PsiComment || type in SqlTokens.COMMENT_TOKENS
        if (!comment && type != SqlTokens.SQL_STRING_TOKEN && type != SqlTokens.SQL_IDENT) return
        val file = element.containingFile ?: return
        if (!file.language.isKindOf(language)) return
        val span = Span.of(file, element.textRange) ?: return
        val text = span.text
        if (text.isEmpty() || !isSpan(comment, type, text)) return
        val lexed = Tokenizer(config).tokenizeForEditor(text)
        if (type == SqlTokens.SQL_IDENT && lexed.tokens.size < 2) return
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile) ?: return
        var covered = 0
        for (token in lexed.tokens) {
            comments(span, covered, token.start, highlighter, holder)
            recolor(holder, span.fileRange(token.start, token.end + 1), attributes(highlighter, platformType(token)))
            covered = token.end + 1
        }
        comments(span, covered, lexed.failureOffset ?: text.length, highlighter, holder)
    }

    private fun isSpan(comment: Boolean, type: IElementType, text: String): Boolean = when {
        comment -> config.comments.keys.none(text::startsWith)
        type == SqlTokens.SQL_STRING_TOKEN -> !startsAsString(text)
        else -> !text.all { it.isLetterOrDigit() || it == '_' || it == '$' }
    }

    /** Non-blank text between tokens is a comment. */
    private fun comments(span: Span, from: Int, to: Int, highlighter: SyntaxHighlighter, holder: AnnotationHolder) {
        var start = from
        var end = to
        while (start < end && span.text[start].isWhitespace()) start++
        while (end > start && span.text[end - 1].isWhitespace()) end--
        if (start < end) recolor(holder, span.fileRange(start, end), attributes(highlighter, SqlTokens.SQL_BLOCK_COMMENT))
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

    private fun recolor(holder: AnnotationHolder, range: TextRange?, key: TextAttributesKey) {
        if (range != null && !range.isEmpty) holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}

/**
 * The SQL [text] of a leaf at [range] in a file, with ranges in it mapped back to the file. An
 * injected leaf reads its host's decoded text and maps through the host's escaper, character by
 * character; a leaf across several host fragments is no span.
 */
private class Span(val text: String, private val range: TextRange, private val toLeaf: (Int) -> Int) {
    fun fileRange(from: Int, to: Int): TextRange? {
        val start = toLeaf(from)
        val end = toLeaf(to)
        return if (start < 0 || end < start) null else TextRange(range.startOffset + start, range.startOffset + end)
    }

    companion object {
        fun of(file: PsiFile, range: TextRange): Span? {
            val manager = InjectedLanguageManager.getInstance(file.project)
            if (!manager.isInjectedFragment(file)) return Span(file.viewProvider.contents.substring(range.startOffset, range.endOffset), range) { it }
            val host = manager.getInjectionHost(file) ?: return null
            val inside = manager.injectedToHost(file, range).shiftLeft(host.textRange.startOffset)
            if (inside.length != range.length || inside.startOffset < 0 || inside.endOffset > host.textLength) return null
            val escaper = host.createLiteralTextEscaper()
            val decoded = StringBuilder()
            if (!escaper.decode(inside, decoded)) return null
            return Span(decoded.toString(), range) { offset -> escaper.getOffsetInHost(offset, inside).let { if (it < 0) it else it - inside.startOffset } }
        }
    }
}

/** A string token starts with a quote, optionally after a prefix of up to two letters or `&`, such as `E'` or `U&'`. */
internal fun startsAsString(text: String): Boolean = quoteAfterPrefix(text) in "'\"`$"

/** The character after a prefix of up to two letters or `&`, or a blank when the text is a word. */
internal fun quoteAfterPrefix(text: String): Char = text.indexOfFirst { !it.isLetter() && it != '&' }.takeIf { it in 0..2 }?.let(text::get) ?: ' '
