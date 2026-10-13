package dev.brikk.house.sql.intellij.host

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiLanguageInjectionHost

/** The SQL of host strings, checked against the host's own decoding and Kotlin's own trims. */
class HostSqlLayoutTest : HostSqlTestCase() {
    private fun decoded(host: PsiLanguageInjectionHost, range: TextRange): String =
        StringBuilder().also { check(host.createLiteralTextEscaper().decode(range, it)) }.toString()

    /** The runtime string of [entries] in [host]: decoded pieces and replacements, then the trims as Kotlin applies them. */
    private fun runtime(host: PsiLanguageInjectionHost, entries: HostSqlEntries): String =
        entries.trims.fold(entries.entries.joinToString("") { it.replacement ?: decoded(host, it.range) }) { text, trim ->
            when (trim) {
                HostSqlTrim.Indent -> text.trimIndent()
                is HostSqlTrim.Margin -> text.trimMargin(trim.prefix)
            }
        }

    /** Checks [entries]: the text is the runtime string, every character with an origin decodes from it, and placeholders are where their text is. */
    private fun check(host: PsiLanguageInjectionHost, entries: HostSqlEntries) {
        val layout = HostSqlInjection.layout(host, entries)
        assertEquals(runtime(host, entries), layout.text)
        for (i in layout.text.indices) {
            val origin = layout.hostRange(i, i + 1) ?: continue
            assertEquals("character $i of ${layout.text}", layout.text[i].toString(), decoded(host, origin))
        }
        val placeholders = entries.entries.filter { it.placeholder }.mapNotNull { it.replacement }
        assertEquals(placeholders, layout.placeholders.map { it.substring(layout.text) })
    }

    private val text = """
        SELECT '
            SELECT a, ''qd'' AS ${'$'}{x},
              b\tc😀
            FROM t\r\n    WHERE d = 1
            ',
          'margin
          |SELECT a,
          |  b😀 -- ${'$'}{y}
             |FROM t\r\n  |WHERE 1 = 1
          '
    """.trimIndent()

    /** Entries of the [nth] literal: its content, with [token] replaced by [replacement] (a placeholder unless said otherwise). */
    private fun entries(nth: Int, token: String?, replacement: String = "rep_1", placeholder: Boolean = true, vararg trims: HostSqlTrim): Pair<PsiLanguageInjectionHost, HostSqlEntries> {
        val host = host(nth)
        val content = TextRange(1, host.textLength - 1)
        if (token == null) return host to HostSqlEntries(listOf(HostSqlEntry(content)), trims.toList())
        val at = host.text.indexOf(token)
        return host to HostSqlEntries(listOf(
            HostSqlEntry(TextRange(content.startOffset, at)),
            HostSqlEntry(TextRange(at, at + token.length), replacement, placeholder),
            HostSqlEntry(TextRange(at + token.length, content.endOffset)),
        ), trims.toList())
    }

    fun testTextAndOriginsFollowEscapesTrimsAndReplacements() {
        open(text)
        for (token in listOf(null, "\${x}")) {
            for (trims in listOf(emptyList(), listOf(HostSqlTrim.Indent))) {
                val (host, entries) = entries(0, token, trims = trims.toTypedArray())
                check(host, entries)
            }
        }
        for (token in listOf(null, "\${y}")) {
            for (trims in listOf(emptyList(), listOf(HostSqlTrim.Margin()), listOf(HostSqlTrim.Margin("|"), HostSqlTrim.Indent))) {
                val (host, entries) = entries(1, token, trims = trims.toTypedArray())
                check(host, entries)
            }
        }
    }

    fun testAnUntrimmedCrLfStaysAndATrimmedOneBecomesALineFeed() {
        open(text)
        val (host, plain) = entries(0, null)
        val untrimmed = HostSqlInjection.layout(host, plain).text
        assertTrue(untrimmed.map { it.code }.toString(), "\r\n" in untrimmed)
        val trimmed = HostSqlInjection.layout(host, plain.copy(trims = listOf(HostSqlTrim.Indent))).text
        assertFalse("\r" in trimmed)
        assertTrue(trimmed, "FROM t\nWHERE d = 1" in trimmed)
    }

    fun testPlacesJoinContiguousOriginsAndCarryTextOfNoOrigin() {
        open("SELECT 'SELECT \${x} FROM t'")
        val (host, entries) = entries(0, "\${x}", "a", placeholder = false)
        val places = HostSqlInjection.places(host, entries)
        assertEquals(listOf(HostSqlPlace("", TextRange(1, 8)), HostSqlPlace("a", TextRange(12, 19))), places)
        // A trailing replacement is the last place's suffix; an all-replaced string is one empty place.
        val end = HostSqlEntries(listOf(HostSqlEntry(TextRange(1, 8)), HostSqlEntry(TextRange(8, 19), "1")))
        assertEquals(listOf(HostSqlPlace("", TextRange(1, 8), "1")), HostSqlInjection.places(host, end))
        val all = HostSqlEntries(listOf(HostSqlEntry(TextRange(1, 19), "SELECT 1")))
        assertEquals(listOf(HostSqlPlace("SELECT 1", TextRange(1, 1))), HostSqlInjection.places(host, all))
    }
}
