package dev.brikk.house.sql.intellij.pipe

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.PipeStageSplitter
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.pipeStageKeywords
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlFragment
import org.jetbrains.annotations.ApiStatus

/**
 * Completes stage phrases right after a top-level `|>` and, once a stage has begun, the columns its
 * input provides. Register it for the dialect's language with a no-arg subclass.
 */
@ApiStatus.Experimental
public abstract class PipeStageCompletionContributor(private val dialect: PipeDialect) : CompletionContributor() {
    private val phrases by lazy { pipeStageKeywords(dialect.dialect) }
    private val inputColumns = object : LinkedHashMap<Pair<String, ShapeCatalog>, List<String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, ShapeCatalog>, List<String>>) = size > 32
    }

    /**
     * Relations that the stage input [input] may read in [file], such as base table columns;
     * null offers no input columns here, for example where another contributor owns them.
     */
    protected open fun inputCatalog(file: PsiFile, input: String): ShapeCatalog? = ShapeCatalog.EMPTY

    final override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!dialect.isEditingEnabled(file)) return
        val source = dialect.pipeText(file)
        val offset = source.textOffset(parameters.offset).coerceIn(0, source.text.length)
        val statement = dialect.statementAt(source.text, offset)?.takeIf { it.hasPipe } ?: return
        val before = source.text.substring(statement.start, offset)
        val boundary = lastTopLevelPipe(before) ?: return
        val typed = before.substring(boundary + 2).trimStart()
        val words = typed.trim().split(WHITESPACE).joinToString(" ")
        val complete = typed.lastOrNull()?.isWhitespace() == true && phrases.any { it.equals(words, ignoreCase = true) }
        val matching = phrases.filter { it.startsWith(words, ignoreCase = true) }
        if (!complete && matching.isNotEmpty()) {
            val sink = result.withPrefixMatcher(PlainPrefixMatcher(typed))
            matching.forEach { sink.addElement(LookupElementBuilder.create(it).withTypeText("PIPE stage", true)) }
            result.stopHere()
            return
        }
        val input = before.substring(0, boundary).trim()
        val catalog = inputCatalog(file, input) ?: return
        val sink = result.caseInsensitive()
        for (column in columns(input, catalog)) sink.addElement(LookupElementBuilder.create(column).withTypeText("PIPE input column", true))
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

    private fun lastTopLevelPipe(text: String): Int? {
        val tokens = try {
            PipeStageSplitter.split(text, dialect.dialect).tokens
        } catch (_: TokenError) {
            return null
        }
        var depth = 0
        var last: Int? = null
        for (token in tokens) when (token.tokenType) {
            TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> depth++
            TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> depth--
            TokenType.PIPE_GT -> if (depth == 0) last = token.start
            else -> {}
        }
        return last
    }
}

/** Colors the stage phrase after every `|>` as a keyword. Register it for the dialect's language with a no-arg subclass. */
@ApiStatus.Experimental
public abstract class PipeStageKeywordAnnotator(private val dialect: PipeDialect) : Annotator {
    private val phrases by lazy {
        pipeStageKeywords(dialect.dialect).map { it.split(' ') }.sortedByDescending { it.size }
    }

    final override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        if (!dialect.isEditingEnabled(file)) return
        val source = dialect.pipeText(file)
        for (statement in dialect.scriptRanges(source.text).filter { it.hasPipe && it.boundaryError == null }) {
            val tokens = try {
                Dialects.forName(dialect.dialect).tokenize(source.text.substring(statement.start, statement.end))
            } catch (_: TokenError) {
                continue
            }
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

private val GENERATED_NAME = Regex("_col_\\d+")
private val WHITESPACE = Regex("\\s+")
