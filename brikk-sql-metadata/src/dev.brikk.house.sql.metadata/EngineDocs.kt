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
 * (`ENGINE = MergeTree`). [properties] are the engine's `PROPERTIES (...)` lists and the keys they take.
 * A dialect's docs hang off its [DialectMetadata.docs].
 */
data class EngineDocs(
    val dialect: String,
    val version: String,
    val base: String,
    val statements: List<DocsEntry>,
    val tableFunctions: Map<String, String> = emptyMap(),
    val engines: Map<String, String> = emptyMap(),
    val release: String? = null,
    val properties: List<PropertyList> = emptyList(),
) {
    // Binary compatibility (AGENTS.md): the constructor and copy from before properties.
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(
        dialect: String,
        version: String,
        base: String,
        statements: List<DocsEntry>,
        tableFunctions: Map<String, String> = emptyMap(),
        engines: Map<String, String> = emptyMap(),
        release: String? = null,
    ) : this(dialect, version, base, statements, tableFunctions, engines, release, emptyList())

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    fun copy(
        dialect: String = this.dialect,
        version: String = this.version,
        base: String = this.base,
        statements: List<DocsEntry> = this.statements,
        tableFunctions: Map<String, String> = this.tableFunctions,
        engines: Map<String, String> = this.engines,
        release: String? = this.release,
    ): EngineDocs = copy(dialect, version, base, statements, tableFunctions, engines, release, properties)

    init {
        require(base.endsWith("/")) { "Docs base must end with '/': $base" }
    }

    /** The absolute URL of [path]. */
    fun url(path: String): String = base + path
}

/**
 * A `PROPERTIES (...)`-style list of `"key" = "value"` pairs and the keys it takes ([keys]), documented on
 * [path] (relative to [EngineDocs.base]). The list is the parenthesis after [opener] (`PROPERTIES`, `KAFKA`,
 * `S3`; alternatives `A|B`, and `BROKER "name" (...)` counts as BROKER) at the top level of a statement
 * whose [context] words (a [DocsPattern]) begin it, or, for a [clause], stand anywhere before the opener
 * (`INTO OUTFILE`). A [variant] applies only when the list sets its key to its value (`"type" = "hms"`);
 * the keys of a statement's lists without a variant always apply. A key named `prefix.*` stands for every
 * key beginning `prefix.`. Keys outside the lists are the engine's to judge: they are never errors.
 */
data class PropertyList(
    val title: String,
    val context: String,
    val path: String,
    val keys: List<PropertyDef>,
    val opener: String = "PROPERTIES",
    val clause: Boolean = false,
    val variant: Pair<String, String>? = null,
) {
    /** [context], compiled. */
    val words: List<DocsPattern> = DocsPattern.parse(context)

    /** [opener]'s alternatives, upper case. */
    val openers: Set<String> = opener.split('|').map(String::uppercase).toSet()
}

/** The definition of [key] among [this], exact or through a `prefix.*` family, ignoring case. */
fun List<PropertyDef>.definitionOf(key: String): PropertyDef? =
    firstOrNull { it.name.equals(key, ignoreCase = true) }
        ?: firstOrNull { it.name.endsWith(".*") && key.startsWith(it.name.dropLast(1), ignoreCase = true) }
