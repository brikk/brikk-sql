package dev.brikk.house.sql.docs

import dev.brikk.house.sql.metadata.DocsEntry
import dev.brikk.house.sql.metadata.ENGINE_DOCS
import dev.brikk.house.sql.metadata.EngineDocs
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs

/** A construct's engine documentation: its [title], one-line [summary] when known and [url]; it is named at `[start, end)`. */
data class DocsLink(val title: String, val summary: String?, val url: String, val start: Int, val end: Int)

/**
 * brikk-native: the engine documentation page for the SQL construct at an offset, from [ENGINE_DOCS]: a
 * statement form named by the statement's leading words (`CREATE JOB`), a clause named anywhere in it
 * (`INTO OUTFILE`), or a table function called in a relation position (`FROM s3(...)`). Read from the
 * dialect's own tokens and statement ranges, so quotes, comments and statement boundaries are the dialect's.
 */
object SqlDocs {
    /** The documentation for what [offset] of [sql] names in [dialect], or null when nothing documented is there. */
    fun at(sql: String, dialect: String, offset: Int, checkCanceled: () -> Unit = {}): DocsLink? {
        val docs = ENGINE_DOCS[dialect] ?: return null
        if (offset !in 0..sql.length) return null
        val range = SqlScriptSnapshot.parse(sql, dialect, checkCanceled).at(minOf(offset, maxOf(sql.length - 1, 0))) ?: return null
        val text = sql.substring(range.start, range.end)
        val tokens = Tokenizer(TokenizerConfigs.forName(dialect)).tokenizeForEditor(text, checkCanceled).tokens
            .filter { it.tokenType != TokenType.SEMICOLON }
        val local = offset - range.start
        val at = tokens.indexOfFirst { local >= it.start && local <= it.end + 1 }.takeIf { it >= 0 } ?: return null
        if (!tokens[at].isWord(text)) return null
        fun link(title: String, summary: String?, path: String, first: Int, last: Int) =
            DocsLink(title, summary, docs.url(path), range.start + tokens[first].start, range.start + tokens[last].end + 1)
        tableFunction(docs, tokens, at, text)?.let { path -> return link(tokens[at].text, null, path, at, at) }
        for (entry in docs.statements) {
            val starts = if (entry.clause) tokens.indices.toList() else listOf(0)
            for (start in starts) {
                if (start > at) break
                val used = match(Pattern.of(entry.pattern), tokens, text, start) ?: continue
                if (at < start + used) return link(entry.title, entry.summary, entry.path, start, start + used - 1)
            }
        }
        return null
    }

    /** The page of the table function named at [at], called in a relation position, or null. */
    private fun tableFunction(docs: EngineDocs, tokens: List<Token>, at: Int, text: String): String? {
        if (tokens.getOrNull(at + 1)?.tokenType != TokenType.L_PAREN) return null
        val path = docs.tableFunctions[tokens[at].text.lowercase()] ?: return null
        val before = tokens.getOrNull(at - 1)?.takeIf { it.isWord(text) }?.text?.uppercase() ?: return null
        return path.takeIf { before in RELATION_POSITIONS }
    }

    /** The number of [tokens] from [from] that [elements] match, preferring the longest; null when they do not. */
    private fun match(elements: List<Pattern>, tokens: List<Token>, text: String, from: Int): Int? {
        if (elements.isEmpty()) return 0
        val rest = elements.drop(1)
        return when (val first = elements.first()) {
            is Pattern.Word -> {
                val token = tokens.getOrNull(from)
                if (token != null && token.isWord(text) && token.text.uppercase() in first.choices) match(rest, tokens, text, from + 1)?.plus(1) else null
            }
            is Pattern.Optional -> match(first.elements + rest, tokens, text, from) ?: match(rest, tokens, text, from)
        }
    }

    /** A bare word as written, not a quoted identifier or a string. */
    private fun Token.isWord(text: String): Boolean =
        tokenType != TokenType.IDENTIFIER && tokenType != TokenType.STRING && WORD.matches(this.text) &&
            text.regionMatches(start, this.text, 0, this.text.length, ignoreCase = true)

    private sealed interface Pattern {
        data class Word(val choices: Set<String>) : Pattern
        data class Optional(val elements: List<Pattern>) : Pattern

        companion object {
            private val cache = HashMap<String, List<Pattern>>()

            fun of(pattern: String): List<Pattern> = synchronized(cache) { cache.getOrPut(pattern) { parse(pattern) } }

            private fun parse(pattern: String): List<Pattern> {
                val words = Regex("""\[|]|[^\s\[\]]+""").findAll(pattern).map { it.value }.toList()
                var at = 0
                fun sequence(nested: Boolean): List<Pattern> = buildList {
                    while (at < words.size) {
                        when (val word = words[at++]) {
                            "[" -> add(Optional(sequence(nested = true)))
                            "]" -> if (nested) return@buildList else throw IllegalArgumentException("Unbalanced ] in '$pattern'")
                            else -> add(Word(word.split('|').map(String::uppercase).toSet()))
                        }
                    }
                    require(!nested) { "Unbalanced [ in '$pattern'" }
                }
                return sequence(nested = false)
            }
        }
    }

    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_$]*")

    /** The words after which a call is a relation: `FROM f(...)`, `JOIN f(...)`, `INSERT INTO [FUNCTION] f(...)`, `TABLE(f(...))`. */
    private val RELATION_POSITIONS = setOf("FROM", "JOIN", "FUNCTION", "INTO", "TABLE", "LATERAL")
}

/** Every bundled [DocsEntry] with its absolute URL, for checks that the pages resolve. */
fun EngineDocs.urls(): List<String> = (statements.map(DocsEntry::path) + tableFunctions.values).distinct().map(::url)
