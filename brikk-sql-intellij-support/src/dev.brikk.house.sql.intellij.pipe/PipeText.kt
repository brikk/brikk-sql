package dev.brikk.house.sql.intellij.pipe

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.parser.SyntacticRange

/** The SQL [text] that PIPE editor features read from [file], with offset mapping both ways. */
public class PipeText private constructor(
    public val file: PsiFile,
    public val text: String,
    private val injection: InjectedLanguageManager?,
) {
    /** The offset in [text] of [fileOffset]. */
    public fun textOffset(fileOffset: Int): Int = injection?.mapInjectedOffsetToUnescaped(file, fileOffset) ?: fileOffset

    /**
     * The file range of `[start, end)` in [text]. Injected text is cut to its first editable part,
     * so an annotation never covers host code; null when no part is editable.
     */
    public fun fileRange(start: Int, end: Int): TextRange? {
        val manager = injection ?: return TextRange(start, end)
        val mapped = TextRange(manager.mapUnescapedOffsetToInjected(file, start), manager.mapUnescapedOffsetToInjected(file, end))
        return manager.intersectWithAllEditableFragments(file, mapped).firstOrNull { !it.isEmpty }
    }

    /**
     * The file range to annotate for an [error] in the statement that starts at [statementStart]
     * in [text] and reads [statement]. A known range is clamped to the statement and widened to at
     * least one character; an unknown one marks the statement's first non-blank character.
     */
    public fun errorRange(statementStart: Int, statement: String, error: SyntacticRange?): TextRange? {
        if (statement.isEmpty()) return null
        val start = error?.start?.coerceIn(0, statement.length) ?: statement.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val first = if (start == statement.length) statement.offsetByCodePoints(start, -1) else start
        val end = maxOf(error?.end ?: 0, statement.offsetByCodePoints(first, 1)).coerceAtMost(statement.length)
        return fileRange(statementStart + first, statementStart + end)
    }

    public companion object {
        /** Injected fragments read their unescaped text; other files read their own. */
        public fun of(file: PsiFile): PipeText {
            val manager = InjectedLanguageManager.getInstance(file.project)
            return if (manager.isInjectedFragment(file)) PipeText(file, manager.getUnescapedText(file), manager)
            else PipeText(file, file.text.orEmpty(), null)
        }
    }
}
