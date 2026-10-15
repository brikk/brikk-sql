package dev.brikk.house.sql.metadata

/**
 * A documented SQL construct: the words naming it, as a [pattern] of whole words, each word's
 * alternatives written `A|B` (`CREATE TABLE|VIEW` is CREATE then TABLE or VIEW; alternation never
 * spans words) and `[...]` making words optional (`CREATE [OR REPLACE] VIEW`); the page documenting it
 * relative to [EngineDocs.base], its [title] and an optional one-line [summary]. A [clause] is named in a
 * clause position of a statement (`INTO OUTFILE`); otherwise the words begin the statement. With a
 * [literalPrefix], the words must be followed by a string literal starting with it (`ATTACH 'ducklake:...'`).
 * With [afterName], they must be followed by an object's name ([IF [NOT] EXISTS] and a dotted name) and
 * then one of these words or symbols: Doris's sync and async `CREATE MATERIALIZED VIEW` differ only there.
 * A null [path] is a form the engine documents nowhere: it links nothing, rather than a more general page
 * (`SET TRANSACTION`, not the page of `SET`). The pattern is compiled, and checked, when the entry is made.
 */
data class DocsEntry(
    val pattern: String,
    val path: String?,
    val title: String,
    val summary: String? = null,
    val clause: Boolean = false,
    val literalPrefix: String? = null,
    val afterName: Set<String>? = null,
) {
    /** [pattern], compiled. */
    val words: List<DocsPattern> = DocsPattern.parse(pattern)
}

/** One element of a [DocsEntry.pattern]: a word with its alternatives, or optional words. */
sealed interface DocsPattern {
    data class Word(val choices: Set<String>) : DocsPattern

    data class Optional(val elements: List<DocsPattern>) : DocsPattern

    companion object {
        private fun flatten(elements: List<DocsPattern>): List<Word> = elements.flatMap {
            when (it) {
                is Word -> listOf(it)
                is Optional -> flatten(it.elements)
            }
        }

        internal fun parse(pattern: String): List<DocsPattern> {
            val words = Regex("""\[|]|[^\s\[\]]+""").findAll(pattern).map { it.value }.toList()
            var at = 0
            fun sequence(nested: Boolean): List<DocsPattern> = buildList {
                while (at < words.size) {
                    when (val word = words[at++]) {
                        "[" -> add(Optional(sequence(nested = true)))
                        "]" -> if (nested) return@buildList else throw IllegalArgumentException("Unbalanced ] in '$pattern'")
                        else -> add(Word(word.split('|').map(String::uppercase).toSet()))
                    }
                }
                require(!nested) { "Unbalanced [ in '$pattern'" }
            }
            val elements = sequence(nested = false)
            require(elements.any { it is Word }) { "'$pattern' has no required word" }
            // A word offered both as an alternative and again later is alternation meant across words
            // (`CHECKPOINT|FORCE CHECKPOINT`); write `[FORCE] CHECKPOINT`.
            val flat = flatten(elements)
            for ((index, word) in flat.withIndex()) {
                val repeated = flat.drop(index + 1).firstOrNull { later -> later.choices.any { it in word.choices } }
                require(repeated == null || word.choices.size == 1 && repeated.choices.size == 1) {
                    "'$pattern' offers ${word.choices.intersect(repeated?.choices.orEmpty())} in two words: alternatives belong to one word"
                }
            }
            return elements
        }
    }
}

/**
 * Where one engine's documentation lives: [base] (ending in `/`) holds the pages of [version], the release
 * line they document (`current` or `latest` where the site documents only its current release, then
 * [release] is the release those pages showed when they were checked). [statements] are
 * tried in order, so a specific form comes before a general one (`SET PASSWORD` before `SET`);
 * [tableFunctions] maps a table function's lowercase name to its page, and [engines] a table engine's
 * (`ENGINE = MergeTree`). A dialect's docs hang off its [DialectMetadata.docs].
 */
data class EngineDocs(
    val dialect: String,
    val version: String,
    val base: String,
    val statements: List<DocsEntry>,
    val tableFunctions: Map<String, String> = emptyMap(),
    val engines: Map<String, String> = emptyMap(),
    val release: String? = null,
) {
    init {
        require(base.endsWith("/")) { "Docs base must end with '/': $base" }
    }

    /** The absolute URL of [path]. */
    fun url(path: String): String = base + path
}
