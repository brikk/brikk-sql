package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.impl.PsiBuilderAdapter
import com.intellij.lang.impl.PsiBuilderImpl
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.lexer.CoreStatements
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import org.jetbrains.annotations.ApiStatus

/**
 * Keeps a parser's top-level statements to the [coreDialect]'s script ranges where the grammar's
 * boundaries are clearly wrong, so a grammar that lacks a clause never splits a statement in two
 * and raw data never joins two. Hold one per parser instance. Core's ranges are read lazily,
 * shared with the lexer's pass over the same text.
 */
@ApiStatus.Experimental
public class StatementBoundaryGate(
    private val coreDialect: String,
    /** Whether a lenient statement makes `:name` binds parameters, as the dialect's IDE binds. */
    private val colonParameters: Boolean = false,
) {
    private var text: CharSequence? = null
    private var statements: CoreStatements? = null

    /** Where the last clean split inside a core range left the next top-level statement, or -1. */
    private var splitAt = -1

    /**
     * Runs [parse] on the statement at [builder]. When the statement starts a core range without a
     * boundary error, [parse] stops before the range's end (before its `;`) and the grammar's split
     * is clearly wrong, or [parse] runs past that end, the statement is parsed again as one lenient
     * [type] node up to that end, keeping user parameters.
     *
     * A split is clearly wrong when the grammar reported an error in the part it parsed (it lacks a
     * clause, such as `PARTITION BY` after `INSERT INTO FUNCTION`), parsed nothing, or stopped where
     * core reads one construct on: inside brackets, or inside a token or comment. Errors are those
     * the platform builder (`PsiBuilderImpl.hasErrorsAfter`) recorded since the statement began,
     * through any `PsiBuilderAdapter`s; a builder without one shows none, so its early stops always
     * count as wrong. Text that the grammar reads cleanly as statements without a `;` between them
     * stays split, as it does in the platform dialects, and the statement after such a split is
     * gated as one starting the range. Nested statements, and ranges whose boundary core cannot
     * prove (an unclosed bracket while typing), keep what [parse] made.
     */
    public fun parse(builder: PsiBuilder, type: IElementType = SqlCompositeElementTypes.SQL_STATEMENT, parse: () -> Boolean): Boolean {
        val range = rangeAt(builder)
        val split = splitAt
        splitAt = -1
        if (range == null || range.boundaryError != null) return parse()
        if (builder.currentOffset != split && !builder.startsStatementAt(range.start)) return parse()
        val source = builder.originalText
        val end = range.terminator ?: range.end
        val start = builder.currentOffset
        val marker = builder.mark()
        val parsed = parse()
        val stop = builder.currentOffset
        val erred = builder.platformBuilder()?.hasErrorsAfter(marker) ?: true
        val reached = builder.consumedEnd() <= end && stop >= end
        // A bind kept in a masked span (`MaskingSqlLexer(keepParameters)`) is no grammar's: only a lenient statement makes it a parameter.
        if (reached && !(colonParameters && erred && builder.hasMaskedBindSince(start))) {
            marker.drop()
            return parsed
        }
        if (stop < end && stop > start && !erred && !continuesAt(source, range, stop)) {
            marker.drop()
            splitAt = stop
            return parsed
        }
        marker.rollbackTo()
        val statement = builder.mark()
        while (!builder.eof() && builder.currentOffset < end) {
            if (!colonParameters || !builder.parseColonParameter(named = true)) builder.advanceLenient()
        }
        statement.done(type)
        return true
    }

    /** Whether core reads [range] as one construct across [offset]: inside brackets, or no core token starts there. */
    private fun continuesAt(source: CharSequence, range: SqlStatementRange, offset: Int): Boolean {
        val tokens = Tokenizer(TokenizerConfigs.forName(coreDialect))
            .tokenizeForEditor(source.subSequence(range.start, range.end).toString(), ProgressManager::checkCanceled).tokens
        val at = offset - range.start
        var depth = 0
        for (token in tokens) {
            if (token.start >= at) return depth > 0 || token.start > at
            when (token.tokenType) {
                TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> depth++
                TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> depth--
                else -> {}
            }
        }
        return true
    }

    private fun rangeAt(builder: PsiBuilder): SqlStatementRange? {
        val source = builder.originalText
        val known = statements?.takeIf { text === source } ?: CoreStatements.of(source, source.length, coreDialect).also {
            text = source
            statements = it
            splitAt = -1
        }
        return known.rangeAt(builder.currentOffset)
    }
}

/**
 * Whether a `:name` bind follows a masked span (a comment token without comment syntax, as
 * `MaskingSqlLexer` emits) between [start] and the current token.
 */
private fun PsiBuilder.hasMaskedBindSince(start: Int): Boolean {
    var step = -1
    while (true) {
        rawLookup(step) ?: return false
        val tokenStart = rawTokenTypeStart(step)
        if (tokenStart < start) return false
        if (rawLookup(step) == SqlTokens.SQL_COLON && rawLookup(step + 1) == SqlTokens.SQL_IDENT && rawLookup(step - 1) == SqlTokens.SQL_BLOCK_COMMENT) {
            val mask = originalText.subSequence(rawTokenTypeStart(step - 1), tokenStart)
            if (!mask.startsWith("/*")) return true
        }
        step--
    }
}

/** The platform builder under any adapters, such as Grammar-Kit's. */
private fun PsiBuilder.platformBuilder(): PsiBuilderImpl? {
    var builder: PsiBuilder = this
    while (builder is PsiBuilderAdapter) builder = builder.delegate
    return builder as? PsiBuilderImpl
}

/** The end of the last token before the current one that is no whitespace or comment, or 0. */
private fun PsiBuilder.consumedEnd(): Int {
    var step = -1
    while (true) {
        val type = rawLookup(step) ?: return 0
        if (type !in SqlTokens.WS_TOKENS && type !in SqlTokens.COMMENT_TOKENS) return rawTokenTypeStart(step + 1)
        step--
    }
}

/** Whether only whitespace and comments lie between [rangeStart] and the current token. */
private fun PsiBuilder.startsStatementAt(rangeStart: Int): Boolean {
    var step = -1
    while (true) {
        val type = rawLookup(step) ?: return true
        if (rawTokenTypeStart(step) < rangeStart) return true
        if (type !in SqlTokens.WS_TOKENS && type !in SqlTokens.COMMENT_TOKENS) return false
        step--
    }
}
