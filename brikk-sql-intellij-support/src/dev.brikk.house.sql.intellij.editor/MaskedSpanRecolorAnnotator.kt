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
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
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
 * read decoded, as each host's escaper decodes it, and annotated only where it lies in host text.
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
            recolor(holder, span.fileRanges(token.start, token.end + 1), attributes(highlighter, platformType(token)))
            covered = token.end + 1
        }
        val failure = lexed.failureOffset ?: text.length
        comments(span, covered, failure, highlighter, holder)
        unclosedType(text, failure)?.let { recolor(holder, span.fileRanges(failure, text.length), attributes(highlighter, it)) }
    }

    /** The kind of an unterminated quoted string or identifier the text ends with from [offset], as while it is typed. */
    private fun unclosedType(text: String, offset: Int): IElementType? = when {
        offset >= text.length -> null
        text[offset] in config.identifiers -> SqlTokens.SQL_IDENT_DELIMITED
        config.quotes.keys.any { text.startsWith(it, offset) } || config.formatStrings.keys.any { text.startsWith(it, offset, ignoreCase = true) } ->
            SqlTokens.SQL_STRING_TOKEN
        else -> null
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
        if (start < end) recolor(holder, span.fileRanges(start, end), attributes(highlighter, SqlTokens.SQL_BLOCK_COMMENT))
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

    private fun recolor(holder: AnnotationHolder, ranges: List<TextRange>, key: TextAttributesKey) {
        for (range in ranges) holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}

/**
 * The SQL [text] of a leaf in a file, made of [pieces], with ranges in it mapped back to the file.
 * An injected leaf may cross several host fragments: each editable part reads its host's decoded
 * text and maps through that host's escaper character by character, and the text between parts
 * that no host holds, such as an interpolation's placeholder, is read as the injected file has it
 * and never annotated.
 */
private class Span(val text: String, private val pieces: List<Piece>) {
    /** Text `[start, end)` of [Span.text]; [toFile] maps an offset relative to [start] into the file, or to a negative value. */
    class Piece(val start: Int, val end: Int, val toFile: ((Int) -> Int)?)

    /** The file ranges of `[from, to)` that lie in editable parts. */
    fun fileRanges(from: Int, to: Int): List<TextRange> = pieces.mapNotNull { piece ->
        val map = piece.toFile ?: return@mapNotNull null
        val start = maxOf(from, piece.start)
        val end = minOf(to, piece.end)
        if (start >= end) return@mapNotNull null
        val fileStart = map(start - piece.start)
        val fileEnd = map(end - piece.start)
        if (fileStart < 0 || fileEnd <= fileStart) null else TextRange(fileStart, fileEnd)
    }

    companion object {
        fun of(file: PsiFile, range: TextRange): Span? {
            val contents = file.viewProvider.contents
            val manager = InjectedLanguageManager.getInstance(file.project)
            if (!manager.isInjectedFragment(file)) {
                return Span(contents.substring(range.startOffset, range.endOffset), listOf(Piece(0, range.length) { range.startOffset + it }))
            }
            val top = manager.getTopLevelFile(file) ?: return null
            val text = StringBuilder()
            val pieces = ArrayList<Piece>()
            var cursor = range.startOffset
            fun gap(to: Int) {
                if (to <= cursor) return
                pieces += Piece(text.length, text.length + to - cursor, null)
                text.append(contents, cursor, to)
            }
            for (part in manager.intersectWithAllEditableFragments(file, range).sortedBy { it.startOffset }) {
                if (part.isEmpty) continue
                gap(part.startOffset)
                val hostRange = manager.injectedToHost(file, part)
                val host = PsiTreeUtil.getParentOfType(top.findElementAt(hostRange.startOffset), PsiLanguageInjectionHost::class.java, false) ?: return null
                val inside = hostRange.shiftLeft(host.textRange.startOffset)
                if (inside.length != part.length || inside.startOffset < 0 || inside.endOffset > host.textLength) return null
                val escaper = host.createLiteralTextEscaper()
                val decoded = StringBuilder()
                if (!escaper.decode(inside, decoded)) return null
                pieces += Piece(text.length, text.length + decoded.length) { offset ->
                    escaper.getOffsetInHost(offset, inside).let { if (it < 0) it else part.startOffset + it - inside.startOffset }
                }
                text.append(decoded)
                cursor = part.endOffset
            }
            gap(range.endOffset)
            return Span(text.toString(), pieces)
        }
    }
}

/** A string token starts with a quote, optionally after a prefix of up to two letters or `&`, such as `E'` or `U&'`. */
internal fun startsAsString(text: String): Boolean = quoteAfterPrefix(text) in "'\"`$"

/** The character after a prefix of up to two letters or `&`, or a blank when the text is a word. */
internal fun quoteAfterPrefix(text: String): Char = text.indexOfFirst { !it.isLetter() && it != '&' }.takeIf { it in 0..2 }?.let(text::get) ?: ' '
