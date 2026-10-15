package dev.brikk.house.sql.docs

import dev.brikk.house.sql.metadata.DocsPattern
import dev.brikk.house.sql.metadata.EngineDocs
import dev.brikk.house.sql.metadata.PropertyDef
import dev.brikk.house.sql.metadata.TableFunctionInfo
import dev.brikk.house.sql.metadata.definitionOf
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs

/**
 * The place of an offset in a list of `"key" = "value"` properties: the list's [title], its documentation [url]
 * and the [keys] it takes; whether the offset is in a [value] (of [key]) or names a key ([key], null while none is
 * typed); the string there, `[start, end)` quotes included (or the offset itself when there is none), its typed
 * [prefix] (between the opening quote and the offset) and whether it is [quoted]; and the keys the list [used].
 */
data class PropertySite(
    val title: String,
    val url: String?,
    val keys: List<PropertyDef>,
    val key: String?,
    val value: Boolean,
    val start: Int,
    val end: Int,
    val prefix: String,
    val quoted: Boolean,
    val used: Set<String>,
) {
    /** The definition of [key], if the list knows it. */
    val definition: PropertyDef? get() = key?.let(keys::definitionOf)
}

/**
 * brikk-native: the property list at [offset] of [sql], by [docs]'s [EngineDocs.properties] and, for a table
 * function's arguments, [tableFunction]; null outside one, or where the list's statement is not certain. Read from
 * the dialect's own tokens, so the dialect's quotes and comments count; a string still being typed counts too.
 */
fun sqlPropertyAt(
    sql: String,
    docs: EngineDocs,
    offset: Int,
    tableFunction: (String) -> TableFunctionInfo? = { null },
    checkCanceled: () -> Unit = {},
): PropertySite? = PropertyRecognizer.at(sql, docs, offset, tableFunction, checkCanceled)

private object PropertyRecognizer {
    fun at(sql: String, docs: EngineDocs, offset: Int, tableFunction: (String) -> TableFunctionInfo?, checkCanceled: () -> Unit): PropertySite? {
        if (offset !in 0..sql.length) return null
        val range = SqlScriptSnapshot.parse(sql, docs.dialect, checkCanceled).at(minOf(offset, maxOf(sql.length - 1, 0))) ?: return null
        val text = sql.substring(range.start, maxOf(range.end, offset))
        val local = offset - range.start
        val config = TokenizerConfigs.forName(docs.dialect)
        val lexed = Tokenizer(config).tokenizeForEditor(text, checkCanceled)
        val tokens = lexed.tokens.filter { it.tokenType != TokenType.SEMICOLON }
        // A string still open at the caret: from its quote to the caret.
        val open = lexed.failureOffset?.takeIf { it < local && text[it] in QUOTES }
        val before = tokens.filter { it.start < (open ?: local) }

        // The innermost bracket open before the offset.
        val stack = ArrayList<Int>()
        for ((index, token) in before.withIndex()) when (token.tokenType) {
            TokenType.L_PAREN -> stack += index
            TokenType.R_PAREN -> stack.removeLastOrNull()
            else -> {}
        }
        val paren = stack.lastOrNull() ?: return null
        val pairs = pairs(tokens, paren, text)
        val (title, url, keys) = list(docs, tokens, paren, stack.size, text, pairs, tableFunction) ?: return null

        // The string at the offset, closed or still open, and what stands before it.
        val at = tokens.indexOfFirst { it.tokenType == TokenType.STRING && local > it.start && local <= it.end }.takeIf { it >= 0 && open == null }
        // A key typed without quotes yet: the word the caret ends.
        val word = before.lastOrNull()?.takeIf { at == null && open == null && it.end + 1 == local && it.isWord(text) && before.size - 1 != paren }
        val previous = before.getOrNull(if (at != null) at - 1 else before.lastIndex - (if (word != null) 1 else 0)) ?: return null
        val stringStart = when {
            open != null -> open
            at != null -> tokens[at].start
            else -> null
        }
        val stringEnd = at?.let { tokens[it].end + 1 } ?: local
        val value = previous.tokenType == TokenType.EQ
        if (!value && previous.tokenType != TokenType.COMMA && before.indexOf(previous) != paren) return null
        val prefix = stringStart?.let { text.substring(it + 1, local) } ?: word?.text.orEmpty()
        val key = if (value) before.getOrNull(before.indexOf(previous) - 1)?.takeIf { it.tokenType == TokenType.STRING }?.text ?: return null
        else at?.let { tokens[it].text } ?: prefix.ifEmpty { null }
        return PropertySite(
            title, url, keys, key, value,
            range.start + (stringStart ?: word?.start ?: local), range.start + stringEnd, prefix, stringStart != null, pairs.keys,
        )
    }

    /** The list the bracket at [paren] opens: its title, URL and keys. */
    private fun list(
        docs: EngineDocs,
        tokens: List<Token>,
        paren: Int,
        depth: Int,
        text: String,
        pairs: Map<String, String>,
        tableFunction: (String) -> TableFunctionInfo?,
    ): Triple<String, String?, List<PropertyDef>>? {
        val name = tokens.getOrNull(paren - 1) ?: return null
        val opener = when {
            name.isWord(text) -> name.text.uppercase()
            // BROKER "name" (...)
            name.tokenType == TokenType.STRING && tokens.getOrNull(paren - 2)?.isWord(text) == true -> tokens[paren - 2].text.uppercase()
            else -> return null
        }
        // A call in a relation position (FROM s3(...), INSERT INTO s3(...)), not WITH S3 (...).
        val call = tokens.getOrNull(paren - 2)?.let {
            it.tokenType == TokenType.COMMA || it.tokenType == TokenType.L_PAREN || it.isWord(text) && it.text.uppercase() in RELATION_WORDS
        } == true
        tableFunction(name.text)?.takeIf { call && name.isWord(text) && it.properties.isNotEmpty() }?.let { function ->
            return Triple("${function.name} properties", docs.tableFunctions[function.name.lowercase()]?.let(docs::url), function.properties)
        }
        if (depth != 1) return null
        val lists = docs.properties.filter { list ->
            val variant = list.variant
            opener in list.openers && (variant == null || pairs[variant.first]?.equals(variant.second, ignoreCase = true) == true) &&
                if (list.clause) (0 until paren).any { matches(list.words, tokens, text, it) } else matches(list.words, tokens, text, 0)
        }
        if (lists.isEmpty()) return null
        val main = lists.firstOrNull { it.variant != null } ?: lists.first()
        return Triple(main.title, docs.url(main.path), lists.flatMap { it.keys }.distinctBy { it.name.lowercase() })
    }

    /** The `"key" = "value"` pairs of the list the bracket at [paren] opens, keys lower case. */
    private fun pairs(tokens: List<Token>, paren: Int, text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var depth = 0
        var i = paren + 1
        while (i < tokens.size) {
            val token = tokens[i]
            when (token.tokenType) {
                TokenType.L_PAREN -> depth++
                TokenType.R_PAREN -> if (depth-- == 0) break
                TokenType.STRING -> if (depth == 0 && tokens.getOrNull(i + 1)?.tokenType == TokenType.EQ) {
                    out[token.text.lowercase()] = tokens.getOrNull(i + 2)?.takeIf { it.tokenType == TokenType.STRING || it.isWord(text) }?.text.orEmpty()
                }
                else -> {}
            }
            i++
        }
        return out
    }

    private fun matches(elements: List<DocsPattern>, tokens: List<Token>, text: String, from: Int): Boolean = match(elements, tokens, text, from) != null

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

    private fun Token.isWord(text: String): Boolean =
        tokenType != TokenType.IDENTIFIER && tokenType != TokenType.STRING && WORD.matches(this.text) &&
            text.regionMatches(start, this.text, 0, this.text.length, ignoreCase = true)

    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_$]*")
    private val QUOTES = setOf('"', '\'')
    private val RELATION_WORDS = setOf("FROM", "JOIN", "INTO", "LATERAL", "FUNCTION")
}
