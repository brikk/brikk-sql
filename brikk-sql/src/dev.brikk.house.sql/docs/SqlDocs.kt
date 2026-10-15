package dev.brikk.house.sql.docs

import dev.brikk.house.sql.metadata.DocsPattern
import dev.brikk.house.sql.metadata.EngineDocs
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs

/** A construct's engine documentation: its [title], one-line [summary] when known and [url]; it is named at `[start, end)`. */
data class DocsLink(val title: String, val summary: String?, val url: String, val start: Int, val end: Int, val kind: DocsKind)

/** What a [DocsLink] documents. */
enum class DocsKind { STATEMENT, CLAUSE, TABLE_FUNCTION, ENGINE }

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
        fun link(title: String, summary: String?, path: String, first: Int, last: Int, kind: DocsKind) =
            DocsLink(title, summary, docs.url(path), range.start + tokens[first].start, range.start + tokens[last].end + 1, kind)
        tableFunction(docs, tokens, at, text)?.let { path -> return link(tokens[at].text, null, path, at, at, DocsKind.TABLE_FUNCTION) }
        engine(docs, tokens, at, text)?.let { path -> return link(tokens[at].text, null, path, at, at, DocsKind.ENGINE) }
        for (entry in docs.statements) {
            val starts = if (entry.clause) tokens.indices.toList() else listOf(0)
            for (start in starts) {
                if (start > at) break
                val used = match(entry.words, tokens, text, start) ?: continue
                val prefix = entry.literalPrefix
                if (prefix != null && !tokens.getOrNull(start + used).isStringStarting(prefix)) continue
                if (entry.clause && !clausePosition(tokens, start, start + used)) continue
                if (at < start + used) {
                    return link(entry.title, entry.summary, entry.path, start, start + used - 1, if (entry.clause) DocsKind.CLAUSE else DocsKind.STATEMENT)
                }
            }
        }
        return null
    }

    /** The page of the table function named at [at], called in a relation position, or null. */
    private fun tableFunction(docs: EngineDocs, tokens: List<Token>, at: Int, text: String): String? {
        if (tokens.getOrNull(at + 1)?.tokenType != TokenType.L_PAREN) return null
        val path = docs.tableFunctions[tokens[at].text.lowercase()] ?: return null
        // The call's name may be qualified (`jdbc0.native_query(...)`): what comes before the qualifiers decides.
        var first = at
        while (tokens.getOrNull(first - 1)?.tokenType == TokenType.DOT && tokens.getOrNull(first - 2)?.let { it.isWord(text) || it.tokenType == TokenType.IDENTIFIER } == true) first -= 2
        return path.takeIf { relationPosition(tokens, first, at, text) }
    }

    /**
     * Whether a call starting at token [first] is a relation: after FROM, JOIN, LATERAL or a comma of a FROM
     * list, inside `FROM TABLE(...)`, after ClickHouse's `INSERT INTO FUNCTION`, or as an insert target given
     * `"key" = "value"` properties (Doris). A table being created or inserted into by name is none.
     */
    private fun relationPosition(tokens: List<Token>, first: Int, name: Int, text: String): Boolean {
        fun word(index: Int) = tokens.getOrNull(index)?.takeIf { it.isWord(text) }?.text?.uppercase()
        val before = tokens.getOrNull(first - 1) ?: return false
        if (before.tokenType == TokenType.L_PAREN) return word(first - 2) == "TABLE" && fromItem(tokens, first - 2, text)
        if (before.tokenType == TokenType.COMMA) return fromItem(tokens, first, text)
        return when (word(first - 1)) {
            "FROM", "JOIN" -> true
            "LATERAL" -> fromItem(tokens, first - 1, text)
            "FUNCTION" -> word(first - 2) == "INTO"
            "INTO" -> tokens.getOrNull(name + 2)?.tokenType == TokenType.STRING && tokens.getOrNull(name + 3)?.tokenType == TokenType.EQ
            else -> false
        }
    }

    /** Whether token [index] begins an item of a FROM clause: right after FROM or JOIN, or after a comma of the FROM list. */
    private fun fromItem(tokens: List<Token>, index: Int, text: String): Boolean {
        var depth = 0
        for (i in index - 1 downTo 0) {
            val token = tokens[i]
            when (token.tokenType) {
                TokenType.R_PAREN -> depth++
                TokenType.L_PAREN -> if (depth == 0) return false else depth--
                else -> if (depth == 0 && token.isWord(text)) {
                    when (token.text.uppercase()) {
                        "FROM", "JOIN" -> return true
                        in CLAUSE_WORDS -> return false
                    }
                }
            }
        }
        return false
    }

    /** The page of the table engine named at [at] after `ENGINE [=]` of a CREATE or ATTACH statement, or null. */
    private fun engine(docs: EngineDocs, tokens: List<Token>, at: Int, text: String): String? {
        val path = docs.engines[tokens[at].text.lowercase()] ?: return null
        val keyword = if (tokens.getOrNull(at - 1)?.tokenType == TokenType.EQ) at - 2 else at - 1
        if (tokens.getOrNull(keyword)?.takeIf { it.isWord(text) }?.text.equals("ENGINE", ignoreCase = true).not()) return null
        if (tokens.first().text.uppercase() !in setOf("CREATE", "ATTACH", "REPLACE")) return null
        return path.takeIf { depth(tokens, keyword) == 0 }
    }

    /** The bracket depth of token [index]. */
    private fun depth(tokens: List<Token>, index: Int): Int = tokens.take(index).sumOf {
        when (it.tokenType) {
            TokenType.L_PAREN -> 1
            TokenType.R_PAREN -> -1
            else -> 0
        }.toInt()
    }

    /**
     * Whether clause words at `[start, end)` stand where a clause can: after the end of a value (a name, a
     * literal, a closing bracket) and before what a clause takes, not as a column, an alias or a function
     * (`SELECT format FROM t`, `SELECT a, format('x')`, `WHERE sample = 1`).
     */
    private fun clausePosition(tokens: List<Token>, start: Int, end: Int): Boolean {
        val before = tokens.getOrNull(start - 1)
        if (before != null && before.tokenType !in VALUE_ENDS) return false
        val after = tokens.getOrNull(end) ?: return false
        if (after.tokenType in NOT_AFTER_CLAUSE) return false
        // A column named like the clause, aliased or followed by FROM: `SELECT x sample FROM t`.
        return after.text.uppercase() !in setOf("FROM", "AS")
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

    /** Tokens a value ends with, after which a clause may begin. */
    private val VALUE_ENDS = setOf(TokenType.VAR, TokenType.IDENTIFIER, TokenType.NUMBER, TokenType.STRING, TokenType.R_PAREN, TokenType.R_BRACKET)

    /** Tokens that show the word before them is a value, not a clause. */
    private val NOT_AFTER_CLAUSE = setOf(TokenType.COMMA, TokenType.DOT, TokenType.R_PAREN, TokenType.EQ)

    /** Words that end a FROM list, walking back: another clause holds what follows them. */
    private val CLAUSE_WORDS = setOf("SELECT", "WHERE", "GROUP", "HAVING", "ORDER", "QUALIFY", "WINDOW", "LIMIT", "ON", "USING", "SET", "VALUES", "INTO", "BY", "TABLE")
}
