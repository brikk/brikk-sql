package dev.brikk.house.sql.intellij.pipe

import com.intellij.lang.PsiBuilder
import com.intellij.psi.impl.source.resolve.FileContextUtil
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlCompositeElementTypes
import dev.brikk.house.sql.intellij.parser.advanceLenient
import dev.brikk.house.sql.intellij.parser.consumeLenient
import dev.brikk.house.sql.parser.SqlStatementRange
import org.jetbrains.annotations.ApiStatus

/**
 * Parses PIPE statements as one node each, bounded by the dialect's statement ranges, so the
 * platform grammar never shreds them. Hold one per parser instance: it caches the ranges of the
 * text being parsed.
 */
@ApiStatus.Experimental
public class PipeStatementGate(private val dialect: PipeDialect) {
    private var text: CharSequence? = null
    private var ranges: List<SqlStatementRange> = emptyList()

    /**
     * Where PIPE editing is enabled for the parsed file, or the project when the builder has no
     * file, parses the statement at [builder] as one [type] node if it holds a pipe or the caller
     * [claim]s it. The node ends at the statement's range end, before a final `;` unless the range
     * has a boundary error; a claimed statement outside every range ends before the next `;`.
     */
    public fun parse(builder: PsiBuilder, claim: Boolean = false, type: IElementType = SqlCompositeElementTypes.SQL_STATEMENT): Boolean {
        if (!isEnabled(builder)) return false
        val source = builder.originalText
        if (!claim && !source.contains("|>")) return false
        if (text !== source) {
            ranges = dialect.scriptRanges(source.toString())
            text = source
        }
        val offset = builder.currentOffset
        val range = ranges.firstOrNull { offset >= it.start && offset < it.end }
        if (range?.hasPipe != true && !claim) return false
        val marker = builder.mark()
        if (range == null) builder.consumeLenient()
        else while (!builder.eof() && builder.currentOffset < range.end &&
            !(range.boundaryError == null && builder.tokenText == ";" && builder.currentOffset == range.end - 1)) builder.advanceLenient()
        marker.done(type)
        return true
    }

    private fun isEnabled(builder: PsiBuilder): Boolean {
        val file = builder.getUserData(FileContextUtil.CONTAINING_FILE_KEY)
        if (file != null) return dialect.isEditingEnabled(file)
        val project = builder.project ?: return false
        return dialect.isEnabled(project)
    }
}
