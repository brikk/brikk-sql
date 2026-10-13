package dev.brikk.house.sql.intellij.host

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiLanguageInjectionHost
import org.jetbrains.annotations.ApiStatus

/**
 * One piece of SQL in a host string: [range] of the host element's text either kept, as the host
 * decodes it (escapes included), or, given a [replacement], replaced by that text, such as a bind
 * marker for an interpolation. A [placeholder] replacement stands for something not known yet; its
 * text is never reported as an error.
 */
@ApiStatus.Experimental
public data class HostSqlEntry(val range: TextRange, val replacement: String? = null, val placeholder: Boolean = false) {
    init {
        require(placeholder.not() || replacement != null) { "A placeholder needs replacement text" }
    }
}

/** A trim the host language applies to the string at runtime, such as Kotlin's `trimIndent()` and `trimMargin()`. */
@ApiStatus.Experimental
public sealed interface HostSqlTrim {
    /** Drops a blank first and last line and the indent common to the non-blank lines. */
    public data object Indent : HostSqlTrim

    /** Drops a blank first and last line, and on each line blanks followed by [prefix]. */
    public data class Margin(val prefix: String = "|") : HostSqlTrim {
        init {
            require(prefix.isNotBlank()) { "A margin prefix must not be blank" }
        }
    }
}

/**
 * The SQL of a host string as a recognizer of the host language describes it: [entries] in host
 * order covering the string's content, then [trims] in the order the host applies them. Trimmed
 * lines join with `\n`, as Kotlin's do; untrimmed text keeps its line breaks, `\r\n` included.
 */
@ApiStatus.Experimental
public data class HostSqlEntries(val entries: List<HostSqlEntry>, val trims: List<HostSqlTrim> = emptyList()) {
    init {
        require(entries.zipWithNext().all { (a, b) -> a.range.endOffset <= b.range.startOffset }) { "Entries must be in host order without overlap" }
    }
}

/**
 * One injection place: [range] of the host element's text injected as it is, after [prefix], text
 * of no host origin (replacements, line breaks a trim joins with). [suffix] is only on the last place.
 */
@ApiStatus.Experimental
public data class HostSqlPlace(val prefix: String, val range: TextRange, val suffix: String = "")

/**
 * A host string's SQL [text] and where it comes from: each character's host offset, or none for
 * text of no host origin; [places] for an injection, and the [placeholders] as ranges of [text].
 */
@ApiStatus.Experimental
public class HostSqlLayout internal constructor(
    public val text: String,
    public val places: List<HostSqlPlace>,
    public val placeholders: List<TextRange>,
    private val starts: IntArray,
    private val ends: IntArray,
) {
    /** The host offset (in the host element's text) where character [index] of [text] starts; null for text of no host origin. */
    public fun hostOffset(index: Int): Int? = starts.getOrNull(index)?.takeIf { it >= 0 }

    /**
     * The host range of `[start, end)` of [text]: from the first character with a host origin to the end
     * of the last one; null when none has one.
     */
    public fun hostRange(start: Int, end: Int): TextRange? {
        val first = (start until end).firstOrNull { starts[it] >= 0 } ?: return null
        val last = (start until end).last { starts[it] >= 0 }
        return TextRange(starts[first], ends[last])
    }
}

/** Builds the SQL of host strings from [HostSqlEntries], with exact host origins through escapes and trims. */
@ApiStatus.Experimental
public object HostSqlInjection {
    /** The injection places of [entries] in [host]; see [layout]. */
    public fun places(host: PsiLanguageInjectionHost, entries: HostSqlEntries): List<HostSqlPlace> = layout(host, entries).places

    /**
     * The SQL of [entries] in [host]: kept ranges decoded by the host's escaper, replacements as given,
     * then the trims. A kept range the escaper cannot decode is taken as it is.
     */
    public fun layout(host: PsiLanguageInjectionHost, entries: HostSqlEntries): HostSqlLayout {
        val escaper = host.createLiteralTextEscaper()
        var chars = entries.entries.flatMap { entry ->
            val range = entry.range
            entry.replacement?.map { Origin(it, range.startOffset, range.startOffset, synthetic = true, placeholder = entry.placeholder) } ?: run {
                val decoded = StringBuilder()
                if (escaper.decode(range, decoded)) {
                    decoded.indices.map { i -> Origin(decoded[i], escaper.getOffsetInHost(i, range), escaper.getOffsetInHost(i + 1, range)) }
                } else {
                    range.substring(host.text).mapIndexed { i, char -> Origin(char, range.startOffset + i, range.startOffset + i + 1) }
                }
            }
        }
        for (trim in entries.trims) chars = trimmed(chars, trim)
        val anchor = entries.entries.firstOrNull()?.range?.startOffset ?: escaper.relevantTextRange.startOffset
        return HostSqlLayout(
            chars.joinToString("") { it.char.toString() },
            places(chars, anchor),
            placeholders(chars),
            IntArray(chars.size) { if (chars[it].synthetic) -1 else chars[it].start },
            IntArray(chars.size) { if (chars[it].synthetic) -1 else chars[it].end },
        )
    }

    private data class Origin(val char: Char, val start: Int, val end: Int, val synthetic: Boolean = false, val placeholder: Boolean = false)

    /** Runs of host characters with contiguous origins become places; text of no origin prefixes the next one. */
    private fun places(chars: List<Origin>, anchor: Int): List<HostSqlPlace> {
        val places = mutableListOf<HostSqlPlace>()
        val pending = StringBuilder()
        var i = 0
        while (i < chars.size) {
            val char = chars[i]
            if (char.synthetic) {
                pending.append(char.char)
                i++
                continue
            }
            val start = char.start
            var end = char.end
            i++
            while (i < chars.size && !chars[i].synthetic && chars[i].start == end) end = chars[i++].end
            places += HostSqlPlace(pending.toString(), TextRange(start, end))
            pending.clear()
        }
        if (pending.isNotEmpty() || places.isEmpty()) {
            val last = places.removeLastOrNull()
            places += last?.copy(suffix = pending.toString()) ?: HostSqlPlace(pending.toString(), TextRange(anchor, anchor))
        }
        return places
    }

    private fun placeholders(chars: List<Origin>): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        var i = 0
        while (i < chars.size) {
            if (!chars[i].placeholder) {
                i++
                continue
            }
            val start = i
            while (i < chars.size && chars[i].placeholder && chars[i].start == chars[start].start) i++
            ranges += TextRange(start, i)
        }
        return ranges
    }

    /** [chars] trimmed as the host language trims at runtime; lines rejoin with a `\n` of no origin unless the break was one. */
    private fun trimmed(chars: List<Origin>, trim: HostSqlTrim): List<Origin> {
        val lines = mutableListOf<List<Origin>>()
        val breaks = mutableListOf<Origin>()
        var start = 0
        var i = 0
        while (i < chars.size) {
            val char = chars[i].char
            if (char == '\n' || char == '\r') {
                lines += chars.subList(start, i)
                if (char == '\r' && chars.getOrNull(i + 1)?.char == '\n') i++
                // A `\n` keeps its origin; a `\r` or `\r\n` becomes a `\n`, at the origin of its last character.
                breaks += chars[i].let { if (it.char == '\n') it else it.copy(char = '\n', synthetic = true) }
                start = i + 1
            }
            i++
        }
        lines += chars.subList(start, chars.size)
        val blank = { line: List<Origin> -> line.all { it.char.isWhitespace() } }
        val indent = lines.filterNot(blank).minOfOrNull { line -> line.takeWhile { it.char.isWhitespace() }.size } ?: 0
        val kept = lines.indices.filterNot { (it == 0 || it == lines.lastIndex) && blank(lines[it]) }
        return kept.flatMapIndexed { position, index ->
            val line = lines[index]
            val cut = when (trim) {
                HostSqlTrim.Indent -> line.drop(indent)
                is HostSqlTrim.Margin -> {
                    val first = line.indexOfFirst { !it.char.isWhitespace() }
                    if (first >= 0 && line.subList(first, line.size).joinToString("") { it.char.toString() }.startsWith(trim.prefix)) {
                        line.drop(first + trim.prefix.length)
                    } else line
                }
            }
            if (position == 0) cut else listOf(breaks[index - 1]) + cut
        }
    }
}
