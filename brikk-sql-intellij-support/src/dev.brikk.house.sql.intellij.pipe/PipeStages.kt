package dev.brikk.house.sql.intellij.pipe

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.completion.SqlLookupStyles
import dev.brikk.house.sql.intellij.completion.lookup
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import dev.brikk.house.sql.parser.pipeStageKeywords
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlFragment
import org.jetbrains.annotations.ApiStatus

/**
 * Completes stage phrases right after a `|>`, hiding every other item there, and, once a stage has
 * begun, the columns its input provides, in the innermost pipeline at the caret. Items take the
 * [SqlLookupKind.PIPE_STAGE] and [SqlLookupKind.PIPE_INPUT_COLUMN] rows of [styles]. Register it
 * for the dialect's language with a no-arg subclass.
 */
@ApiStatus.Experimental
public abstract class PipeStageCompletionContributor(
    private val dialect: PipeDialect,
    private val styles: SqlLookupStyles = SqlLookupStyles.DEFAULT,
) : CompletionContributor() {
    private val phrases by lazy { pipeStageKeywords(dialect.dialect) }
    private val inputColumns = object : LinkedHashMap<Pair<String, ShapeCatalog>, List<String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, ShapeCatalog>, List<String>>) = size > 32
    }

    /**
     * Relations that the stage input [input] may read in [file], such as base table columns; null
     * offers no input columns. By default none where [PipeDialect.enablesEditing] enables the file,
     * whose own completion owns its columns.
     */
    protected open fun inputCatalog(file: PsiFile, input: String): ShapeCatalog? =
        if (dialect.enablesEditing(file)) null else ShapeCatalog.EMPTY

    final override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile
        if (!dialect.isEditingEnabled(file)) return
        val source = dialect.pipeText(file)
        val offset = source.textOffset(parameters.offset).coerceIn(0, source.text.length)
        val statement = dialect.statementAt(source.text, offset)?.takeIf { it.hasPipe } ?: return
        val before = source.text.substring(statement.start, offset)
        val stage = currentStage(before) ?: return
        val typed = before.substring(stage.typedStart)
        val words = typed.replace(WHITESPACE, " ")
        val complete = words.endsWith(' ') && phrases.any { it.equals(words.trimEnd(), ignoreCase = true) }
        val matching = phrases.filter { it.startsWith(words, ignoreCase = true) }
        if (!complete && matching.isNotEmpty()) {
            val sink = result.withPrefixMatcher(PhrasePrefixMatcher(typed))
            val style = styles[SqlLookupKind.PIPE_STAGE]
            matching.forEach { sink.addElement(style.lookup(StagePhrase(it), it, style.typeText)) }
            result.stopHere()
            return
        }
        val input = before.substring(stage.inputStart, stage.pipe).trim()
        val catalog = inputCatalog(file, input) ?: return
        val sink = result.caseInsensitive()
        val style = styles[SqlLookupKind.PIPE_INPUT_COLUMN]
        for (column in columns(input, catalog)) sink.addElement(style.lookup(column))
    }

    private fun columns(input: String, catalog: ShapeCatalog): List<String> {
        val key = input to catalog
        synchronized(inputColumns) { inputColumns[key] }?.let { return it }
        val names = try {
            SqlFragment(input, dialect.dialect).outputShape(catalog).names()
        } catch (_: ParseError) {
            return emptyList()
        } catch (_: TokenError) {
            return emptyList()
        } catch (_: ShapeError) {
            return emptyList()
        }
        // `_col_N` names exist only in generated SQL; PIPE text cannot refer to them.
        val typeable = names.filter { it != "*" && !GENERATED_NAME.matches(it) }.distinct()
        return typeable.also { synchronized(inputColumns) { inputColumns[key] = it } }
    }

    /**
     * The current stage of the innermost pipeline at the end of [text], if a `|>` starts it. A
     * pipeline is the top level or a bracket that holds a query or a `|>`; other brackets are
     * expressions within the enclosing pipeline's stage. The stage's typed text starts at its
     * first token, so comments after `|>` stay.
     */
    private fun currentStage(text: String): Stage? {
        val lexed = Tokenizer(TokenizerConfigs.forName(dialect.dialect)).tokenizeForEditor(text)
        // The text ends at the caret, so a lexical failure means the caret is inside an unterminated construct.
        if (lexed.failureOffset != null) return null
        val brackets = ArrayDeque(listOf(Bracket(0, pipeline = true)))
        for (token in lexed.tokens) {
            brackets.last().let { if (it.first) { it.first = false; if (token.tokenType in QUERY_HEADS) it.pipeline = true } }
            brackets.last { it.pipeline }.let { if (it.pipe != null && it.typedStart == null && token.tokenType != TokenType.PIPE_GT) it.typedStart = token.start }
            when (token.tokenType) {
                TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> brackets.addLast(Bracket(token.end + 1, pipeline = false))
                TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> if (brackets.size > 1) brackets.removeLast()
                TokenType.PIPE_GT -> brackets.last().apply { pipeline = true; pipe = token.start; typedStart = null }
                else -> {}
            }
        }
        val pipeline = brackets.last { it.pipeline }
        return pipeline.pipe?.let { Stage(pipeline.start, it, pipeline.typedStart ?: text.length) }
    }

    private class Bracket(val start: Int, var pipeline: Boolean) {
        var first = true
        var pipe: Int? = null
        var typedStart: Int? = null
    }

    private class Stage(val inputStart: Int, val pipe: Int, val typedStart: Int)
}

/** A stage phrase item's identity, so keyword styling and other contributors never take it for a plain keyword. */
private data class StagePhrase(val phrase: String)

/** Matches phrases ignoring case and the amount of whitespace, so a typed `full  outer` still replaces exactly what was typed. */
private class PhrasePrefixMatcher(prefix: String) : PrefixMatcher(prefix) {
    private val words = prefix.replace(WHITESPACE, " ")
    override fun prefixMatches(name: String): Boolean = name.startsWith(words, ignoreCase = true)
    override fun cloneWithPrefix(prefix: String): PrefixMatcher = PhrasePrefixMatcher(prefix)
}

/** Colors the stage phrase after every `|>` as a keyword, also in a statement still being typed. Register it for the dialect's language with a no-arg subclass. */
@ApiStatus.Experimental
public abstract class PipeStageKeywordAnnotator(private val dialect: PipeDialect) : Annotator {
    private val phrases by lazy {
        pipeStageKeywords(dialect.dialect).map { it.split(' ') }.sortedByDescending { it.size }
    }

    final override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        if (!dialect.isEditingEnabled(file)) return
        val source = dialect.pipeText(file)
        // Coloring tolerates incomplete input: a statement still being typed keeps the phrases
        // proven before its first lexical problem. Only execution refuses unknown boundaries.
        for (statement in dialect.scriptRanges(source.text).filter { it.hasPipe }) {
            val tokens = Tokenizer(TokenizerConfigs.forName(dialect.dialect))
                .tokenizeForEditor(source.text.substring(statement.start, statement.end)).tokens
            for ((index, token) in tokens.withIndex()) {
                if (token.tokenType != TokenType.PIPE_GT) continue
                val stage = tokens.subList(index + 1, tokens.size)
                for (keyword in phrases.firstNotNullOfOrNull { stage.leading(it) }.orEmpty()) {
                    val range = source.fileRange(statement.start + keyword.start, statement.start + keyword.end + 1) ?: continue
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range)
                        .textAttributes(DefaultLanguageHighlighterColors.KEYWORD).create()
                }
            }
        }
    }
}

/** The tokens spelling [phrase] at the start of this list; a tokenizer may keep several words in one token. */
private fun List<Token>.leading(phrase: List<String>): List<Token>? {
    var matched = 0
    for ((index, token) in withIndex()) {
        val words = token.text.split(WHITESPACE)
        if (matched + words.size > phrase.size || words.withIndex().any { (i, word) -> !word.equals(phrase[matched + i], true) }) return null
        matched += words.size
        if (matched == phrase.size) return subList(0, index + 1)
    }
    return null
}

private val QUERY_HEADS = setOf(TokenType.SELECT, TokenType.FROM, TokenType.WITH, TokenType.VALUES)
private val GENERATED_NAME = Regex("_col_\\d+")
private val WHITESPACE = Regex("\\s+")
