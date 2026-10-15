package dev.brikk.house.sql.docs

import dev.brikk.house.sql.metadata.DocsPattern
import dev.brikk.house.sql.metadata.EngineDocs
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs

/** A construct's engine documentation: its [title], one-line [summary] when known and [url]; it is named at `[start, end)`. */
data class DocsLink(val title: String, val summary: String?, val url: String, val start: Int, val end: Int)

/**
 * brikk-native: the page of [docs] (a dialect's [dev.brikk.house.sql.metadata.DialectMetadata.docs]) for the
 * SQL construct at [offset] of [sql], or null when nothing documented is certainly there: a statement form
 * named by the statement's leading words (`CREATE JOB`), a clause named in it (`INTO OUTFILE`), a table
 * function called in a relation position (`FROM s3(...)`) or a table engine (`ENGINE = MergeTree`). Read
 * from the dialect's own tokens and statement ranges, so quotes, comments and statement boundaries are the
 * dialect's.
 */
fun sqlDocsAt(sql: String, docs: EngineDocs, offset: Int, checkCanceled: () -> Unit = {}): DocsLink? = DocsRecognizer.at(sql, docs, offset, checkCanceled)

private object DocsRecognizer {
    fun at(sql: String, docs: EngineDocs, offset: Int, checkCanceled: () -> Unit): DocsLink? {
        val dialect = docs.dialect
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
        engine(docs, tokens, at, text)?.let { path -> return link(tokens[at].text, null, path, at, at) }
        for (entry in docs.statements) {
            val starts = if (entry.clause) tokens.indices.toList() else listOf(0)
            for (start in starts) {
                if (start > at) break
                val used = match(entry.words, tokens, text, start) ?: continue
                val prefix = entry.literalPrefix
                if (prefix != null && !tokens.getOrNull(start + used).isStringStarting(prefix)) continue
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

    /** The page of the table engine named at [at] after `ENGINE [=]`, or null. */
    private fun engine(docs: EngineDocs, tokens: List<Token>, at: Int, text: String): String? {
        val path = docs.engines[tokens[at].text.lowercase()] ?: return null
        val keyword = if (tokens.getOrNull(at - 1)?.tokenType == TokenType.EQ) at - 2 else at - 1
        return path.takeIf { tokens.getOrNull(keyword)?.takeIf { it.isWord(text) }?.text.equals("ENGINE", ignoreCase = true) }
    }

    /** The number of [tokens] from [from] that [elements] match, preferring the longest; null when they do not. */
    private fun match(elements: List<DocsPattern>, tokens: List<Token>, text: String, from: Int): Int? {
        if (elements.isEmpty()) return 0
        val rest = elements.drop(1)
        return when (val first = elements.first()) {
            is DocsPattern.Word -> {
                val token = tokens.getOrNull(from)
                if (token != null && token.isWord(text) && token.text.uppercase() in first.choices) match(rest, tokens, text, from + 1)?.plus(1) else null
            }
            is DocsPattern.Optional -> match(first.elements + rest, tokens, text, from) ?: match(rest, tokens, text, from)
        }
    }

    /** Whether this is a string literal whose value starts with [prefix], ignoring case. */
    private fun Token?.isStringStarting(prefix: String): Boolean =
        this != null && tokenType == TokenType.STRING && text.startsWith(prefix, ignoreCase = true)

    /** A bare word as written, not a quoted identifier or a string. */
    private fun Token.isWord(text: String): Boolean =
        tokenType != TokenType.IDENTIFIER && tokenType != TokenType.STRING && WORD.matches(this.text) &&
            text.regionMatches(start, this.text, 0, this.text.length, ignoreCase = true)

    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_$]*")

    /** The words after which a call is a relation: `FROM f(...)`, `JOIN f(...)`, `INSERT INTO [FUNCTION] f(...)`, `TABLE(f(...))`. */
    private val RELATION_POSITIONS = setOf("FROM", "JOIN", "FUNCTION", "INTO", "TABLE", "LATERAL")
}
