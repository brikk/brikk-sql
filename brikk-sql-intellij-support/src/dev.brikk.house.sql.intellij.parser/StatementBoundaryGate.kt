package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.SqlStatementRange
import org.jetbrains.annotations.ApiStatus

/**
 * Keeps a parser's top-level statements to the [coreDialect]'s script ranges, so a grammar that
 * lacks a clause never splits a statement in two (and never joins two). Hold one per parser
 * instance: it caches the ranges of the text being parsed.
 */
@ApiStatus.Experimental
public class StatementBoundaryGate(private val coreDialect: String) {
    private var text: CharSequence? = null
    private var ranges: List<SqlStatementRange> = emptyList()

    /**
     * Runs [parse] on the statement at [builder]. When the statement starts a core range without a
     * boundary error and [parse] stops anywhere but at the range's end (before its `;`), the
     * statement is parsed again as one lenient [type] node up to that end, keeping user parameters.
     * Nested statements, and ranges whose boundary core cannot prove, keep what [parse] made.
     */
    public fun parse(builder: PsiBuilder, type: IElementType = SqlCompositeElementTypes.SQL_STATEMENT, parse: () -> Boolean): Boolean {
        val range = rangeAt(builder)
        if (range == null || range.boundaryError != null || !builder.startsStatementAt(range.start)) return parse()
        val source = builder.originalText
        val end = if (source[range.end - 1] == ';') range.end - 1 else range.end
        val marker = builder.mark()
        val parsed = parse()
        if (builder.currentOffset == end) {
            marker.drop()
            return parsed
        }
        marker.rollbackTo()
        val statement = builder.mark()
        while (!builder.eof() && builder.currentOffset < end) builder.advanceLenient()
        statement.done(type)
        return true
    }

    private fun rangeAt(builder: PsiBuilder): SqlStatementRange? {
        val source = builder.originalText
        if (text !== source) {
            text = source
            ranges = SqlScriptSnapshot.parse(source.toString(), coreDialect).statements
        }
        val offset = builder.currentOffset
        return ranges.binarySearch { if (it.end <= offset) -1 else if (it.start > offset) 1 else 0 }.let(ranges::getOrNull)
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
