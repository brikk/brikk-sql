package dev.brikk.house.sql.intellij.catalog

import org.jetbrains.annotations.ApiStatus

/** What an executed statement changes. */
@ApiStatus.Experimental
public sealed interface StatementEffect {
    /** The functions a session can call: an installed or loaded extension, a created or dropped function or macro. */
    public data object FunctionInventory : StatementEffect

    /**
     * Namespaces: the statement creates, drops or reloads the namespace its name addresses at [depth]
     * (1: top level, such as a catalog), so the namespace at [refreshDepth] must reload (0: the data
     * source's top-level list). Creating a schema is `Namespace(2)`, reloading a catalog `Namespace(1, 1)`.
     */
    public data class Namespace(val depth: Int, val refreshDepth: Int = depth - 1) : StatementEffect {
        init {
            require(depth >= 1 && refreshDepth in 0..depth) { "Invalid namespace effect: depth $depth, refresh depth $refreshDepth" }
        }
    }
}

/**
 * A statement head and its effect. [pattern] lists the head's words: `A|B` accepts either, `[...]`
 * makes words optional (`CREATE [OR REPLACE] [TEMP|TEMPORARY] MACRO|FUNCTION`). Words match ignoring case.
 */
@ApiStatus.Experimental
public class StatementRule(public val pattern: String, public val effect: StatementEffect) {
    internal val elements: List<Element> = parse(pattern)

    internal sealed interface Element {
        data class Word(val choices: Set<String>) : Element
        data class Optional(val elements: List<Element>) : Element
    }

    override fun toString(): String = "$pattern -> $effect"

    private companion object {
        fun parse(pattern: String): List<Element> {
            val tokens = Regex("""\[|]|[^\s\[\]]+""").findAll(pattern).map { it.value }.toList()
            var at = 0
            fun sequence(nested: Boolean): List<Element> = buildList {
                while (at < tokens.size) {
                    when (val token = tokens[at++]) {
                        "[" -> add(Element.Optional(sequence(nested = true)))
                        "]" -> if (nested) return@buildList else throw IllegalArgumentException("Unbalanced ] in '$pattern'")
                        else -> add(Element.Word(token.split('|').map(String::uppercase).toSet()))
                    }
                }
                require(!nested) { "Unbalanced [ in '$pattern'" }
            }
            return sequence(nested = false).also { require(it.any { element -> element is Element.Word }) { "'$pattern' has no required word" } }
        }
    }
}

/** A statement whose head matched [rule], with the qualified [name] after the head: unquoted parts, empty when none follows. */
@ApiStatus.Experimental
public data class DetectedStatement(val rule: StatementRule, val name: List<String>) {
    val effect: StatementEffect get() = rule.effect
}

/**
 * Finds the statements of an executed script whose heads match [rules], cheaply enough to run on
 * every execution: no parse, only the leading words of each statement. Statements end at `;` outside
 * quotes and comments; leading comments are skipped; heads match whole words only, so `install_log`
 * or `INSTALLER` never match `INSTALL`. After the head, `IF [NOT] EXISTS` is skipped and the qualified
 * name read, its parts quoted with [quoteChars] (a doubled quote stands for itself) or bare.
 */
@ApiStatus.Experimental
public class StatementHeadDetector(public val rules: List<StatementRule>, private val quoteChars: String = "\"") {
    public fun detect(script: String?): List<DetectedStatement> {
        if (script.isNullOrBlank() || rules.isEmpty()) return emptyList()
        return statements(script).mapNotNull { statement ->
            val scanner = Scanner(statement, quoteChars)
            val words = scanner.leadingWords()
            rules.firstNotNullOfOrNull { rule -> match(rule.elements, words, 0)?.let { used -> DetectedStatement(rule, scanner.nameAfter(used)) } }
        }
    }

    /** The number of [words] from [from] that [elements] match, preferring the longest; null when they do not. */
    private fun match(elements: List<StatementRule.Element>, words: List<String>, from: Int): Int? {
        if (elements.isEmpty()) return from
        val rest = elements.drop(1)
        return when (val first = elements.first()) {
            is StatementRule.Element.Word -> if (from < words.size && words[from] in first.choices) match(rest, words, from + 1) else null
            is StatementRule.Element.Optional -> match(first.elements + rest, words, from) ?: match(rest, words, from)
        }
    }

    private fun statements(script: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        var at = 0
        while (at < script.length) {
            at = when {
                script[at] == ';' -> {
                    result += script.substring(start, at)
                    start = at + 1
                    at + 1
                }
                else -> skipQuotedOrComment(script, at) ?: (at + 1)
            }
        }
        result += script.substring(start)
        return result
    }

    private fun skipQuotedOrComment(text: String, at: Int): Int? {
        val c = text[at]
        return when {
            text.startsWith("--", at) -> text.indexOf('\n', at).let { if (it < 0) text.length else it + 1 }
            text.startsWith("/*", at) -> text.indexOf("*/", at + 2).let { if (it < 0) text.length else it + 2 }
            c == '\'' || c in quoteChars || c == '"' || c == '`' -> closingQuote(text, at)
            c == '$' -> dollarQuoteEnd(text, at)
            else -> null
        }
    }

    /** After the quote opened at [at]; a doubled quote stands for itself. */
    private fun closingQuote(text: String, at: Int): Int {
        val quote = text[at]
        var i = at + 1
        while (i < text.length) {
            if (text[i] == quote) {
                if (i + 1 < text.length && text[i + 1] == quote) i += 2 else return i + 1
            } else {
                i++
            }
        }
        return text.length
    }

    /** After a `$tag$...$tag$` string opened at [at], or null when no dollar quote opens there. */
    private fun dollarQuoteEnd(text: String, at: Int): Int? {
        val close = text.indexOf('$', at + 1).takeIf { it > 0 } ?: return null
        val tag = text.substring(at, close + 1)
        if (!tag.drop(1).dropLast(1).all { it.isLetterOrDigit() || it == '_' }) return null
        return text.indexOf(tag, close + 1).let { if (it < 0) text.length else it + tag.length }
    }

    private inner class Scanner(private val text: String, private val quotes: String) {
        private var at = 0
        private val wordEnds = ArrayList<Int>()

        /** The statement's leading words, uppercased, up to the first token that is not a word. */
        fun leadingWords(): List<String> = buildList {
            while (true) {
                skipTrivia()
                val word = bareWord() ?: break
                add(word.uppercase())
                wordEnds += at
            }
        }

        /** The qualified name after the first [used] words, skipping `IF [NOT] EXISTS`. */
        fun nameAfter(used: Int): List<String> {
            at = if (used == 0) 0 else wordEnds[used - 1]
            skipTrivia()
            val mark = at
            if (bareWord().equals("IF", ignoreCase = true)) {
                skipTrivia()
                var word = bareWord()
                if (word.equals("NOT", ignoreCase = true)) {
                    skipTrivia()
                    word = bareWord()
                }
                if (!word.equals("EXISTS", ignoreCase = true)) at = mark
            } else {
                at = mark
            }
            return buildList {
                while (true) {
                    skipTrivia()
                    add(part() ?: break)
                    skipTrivia()
                    if (at < text.length && text[at] == '.') at++ else break
                }
            }
        }

        private fun part(): String? {
            if (at >= text.length) return null
            val quote = text[at]
            if (quote !in quotes) return bareWord()
            val end = closingQuote(text, at)
            if (end > text.length || text[end - 1] != quote || end - at < 2) return null
            return text.substring(at + 1, end - 1).replace("$quote$quote", "$quote").also { at = end }
        }

        private fun bareWord(): String? {
            val start = at
            if (at >= text.length || !(text[at].isLetter() || text[at] == '_')) return null
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_' || text[at] == '$')) at++
            return text.substring(start, at)
        }

        private fun skipTrivia() {
            while (at < text.length) {
                at = when {
                    text[at].isWhitespace() -> at + 1
                    text.startsWith("--", at) || text.startsWith("/*", at) -> skipQuotedOrComment(text, at) ?: return
                    else -> return
                }
            }
        }
    }
}

/** Statement rules for the dialects of the Sortdev plugins. */
@ApiStatus.Experimental
public object StatementRules {
    private val inventory = StatementEffect.FunctionInventory

    /** DuckDB: installing or loading extensions, creating or dropping macros and functions; attaching and detaching databases. */
    public val DUCKDB: List<StatementRule> = listOf(
        StatementRule("INSTALL", inventory),
        StatementRule("FORCE INSTALL", inventory),
        StatementRule("LOAD", inventory),
        StatementRule("CREATE [OR REPLACE] [TEMP|TEMPORARY] MACRO|FUNCTION", inventory),
        StatementRule("DROP MACRO|FUNCTION", inventory),
        StatementRule("ATTACH [DATABASE]", StatementEffect.Namespace(1)),
        StatementRule("DETACH [DATABASE]", StatementEffect.Namespace(1)),
    )

    /** Trino: creating or dropping catalogs and schemas; creating or dropping functions. */
    public val TRINO: List<StatementRule> = listOf(
        StatementRule("CREATE CATALOG", StatementEffect.Namespace(1)),
        StatementRule("DROP CATALOG", StatementEffect.Namespace(1)),
        StatementRule("CREATE SCHEMA", StatementEffect.Namespace(2)),
        StatementRule("DROP SCHEMA", StatementEffect.Namespace(2)),
        StatementRule("CREATE [OR REPLACE] FUNCTION", inventory),
        StatementRule("DROP FUNCTION", inventory),
    )

    /** Doris: creating, dropping or refreshing catalogs. */
    public val DORIS: List<StatementRule> = listOf(
        StatementRule("CREATE CATALOG", StatementEffect.Namespace(1)),
        StatementRule("DROP CATALOG", StatementEffect.Namespace(1)),
        StatementRule("REFRESH CATALOG", StatementEffect.Namespace(1, refreshDepth = 1)),
    )
}
