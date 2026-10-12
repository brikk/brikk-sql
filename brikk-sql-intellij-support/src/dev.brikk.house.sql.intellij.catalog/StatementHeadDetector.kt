package dev.brikk.house.sql.intellij.catalog

import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfigs
import org.jetbrains.annotations.ApiStatus

/** What an executed statement changes. */
@ApiStatus.Experimental
public sealed interface StatementEffect {
    /**
     * The functions a session can call: an installed or loaded extension, a created or dropped function
     * or macro. With [sessionFacts], the change may belong to the session (a temporary macro, an extension
     * it loaded), so the harvest runs over the session's own connection.
     */
    public data class FunctionInventory(val sessionFacts: Boolean = false) : StatementEffect

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

    /** Transaction control: the statement [begins] a transaction, or ends one (`false`); `null` leaves it as it is. */
    public data class Transaction(val begins: Boolean?) : StatementEffect
}

/**
 * A statement head and its effect. [pattern] lists the head's words: `A|B` accepts either, `[...]`
 * makes words optional (`CREATE [OR REPLACE] TEMP|TEMPORARY MACRO|FUNCTION`). Words match ignoring case.
 * With [notifies], a refresh after the statement shows its result in the live catalog's notification group.
 */
@ApiStatus.Experimental
public class StatementRule(public val pattern: String, public val effect: StatementEffect, public val notifies: Boolean = false) {
    internal val elements: List<Element> = parse(pattern)

    internal sealed interface Element {
        data class Word(val choices: Set<String>) : Element
        data class Optional(val elements: List<Element>) : Element
    }

    /** This rule with [notifies] set. */
    public fun notifying(): StatementRule = StatementRule(pattern, effect, notifies = true)

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
 * every execution: the core tokenizer of [dialect] (a core dialect name such as `duckdb`) reads the
 * script, so its quotes, escape strings and nested comments are the dialect's own, and only the leading
 * words of each statement are compared, whole. After the head, `IF [NOT] EXISTS` is skipped and the
 * qualified name read. Text the tokenizer cannot read past ends the scan there.
 */
@ApiStatus.Experimental
public class StatementHeadDetector(public val rules: List<StatementRule>, public val dialect: String) {
    private val config = TokenizerConfigs.forName(dialect)

    public fun detect(script: String?): List<DetectedStatement> {
        if (script.isNullOrBlank() || rules.isEmpty()) return emptyList()
        val tokens = Tokenizer(config).tokenizeForEditor(script).tokens
        return statements(tokens).mapNotNull { statement ->
            val words = statement.takeWhile { it.isWord(script) }.map { it.text.uppercase() }
            rules.firstNotNullOfOrNull { rule -> match(rule.elements, words, 0)?.let { used -> DetectedStatement(rule, nameAfter(statement.drop(used), script)) } }
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

    private fun statements(tokens: List<Token>): List<List<Token>> {
        val result = ArrayList<List<Token>>()
        var current = ArrayList<Token>()
        for (token in tokens) {
            if (token.tokenType == TokenType.SEMICOLON) {
                result += current
                current = ArrayList()
            } else {
                current += token
            }
        }
        result += current
        return result.filter { it.isNotEmpty() }
    }

    private fun nameAfter(tokens: List<Token>, script: String): List<String> {
        var rest = tokens
        fun word(index: Int) = rest.getOrNull(index)?.takeIf { it.isWord(script) }?.text?.uppercase()
        if (word(0) == "IF" && word(1) == "EXISTS") rest = rest.drop(2)
        else if (word(0) == "IF" && word(1) == "NOT" && word(2) == "EXISTS") rest = rest.drop(3)
        val parts = ArrayList<String>()
        var at = 0
        while (at < rest.size) {
            val part = rest[at]
            parts += when {
                part.tokenType == TokenType.IDENTIFIER -> part.text
                part.isWord(script) -> part.text
                else -> break
            }
            if (rest.getOrNull(at + 1)?.tokenType != TokenType.DOT) break
            at += 2
        }
        return parts
    }

    /** A bare word as written: neither a quoted identifier nor a string, whose token text differs from its source. */
    private fun Token.isWord(script: String): Boolean =
        tokenType != TokenType.IDENTIFIER && WORD.matches(text) &&
            end - start + 1 == text.length && script.regionMatches(start, text, 0, text.length)

    private companion object {
        val WORD = Regex("[A-Za-z_][A-Za-z0-9_$]*")
    }
}

/** Statement rules for the dialects of the Sortdev plugins; none notifies (see [StatementRule.notifying]). */
@ApiStatus.Experimental
public object StatementRules {
    /**
     * Transaction control most engines share: `BEGIN` and `START TRANSACTION` begin; `COMMIT`,
     * `ROLLBACK`, `ABORT` and `END` end; rolling back to a savepoint keeps the transaction, and an end
     * `AND CHAIN` starts the next one at once.
     */
    public val TRANSACTIONS: List<StatementRule> = listOf(
        StatementRule("BEGIN [TRANSACTION|WORK]", StatementEffect.Transaction(begins = true)),
        StatementRule("START TRANSACTION", StatementEffect.Transaction(begins = true)),
        StatementRule("ROLLBACK [TRANSACTION|WORK] TO", StatementEffect.Transaction(begins = null)),
        StatementRule("COMMIT|ROLLBACK|ABORT|END [TRANSACTION|WORK] AND CHAIN", StatementEffect.Transaction(begins = true)),
        StatementRule("COMMIT|ROLLBACK|ABORT|END [TRANSACTION|WORK]", StatementEffect.Transaction(begins = false)),
    )

    private val server = StatementEffect.FunctionInventory(sessionFacts = false)
    private val session = StatementEffect.FunctionInventory(sessionFacts = true)

    /**
     * DuckDB: installing extensions and creating persistent macros change the server's functions; loading
     * extensions and creating temporary macros, or dropping any, may change only the session's. Attaching
     * and detaching databases change the top-level namespaces.
     */
    public val DUCKDB: List<StatementRule> = listOf(
        StatementRule("INSTALL", server),
        StatementRule("FORCE INSTALL", server),
        StatementRule("LOAD", session),
        StatementRule("CREATE [OR REPLACE] TEMP|TEMPORARY MACRO|FUNCTION", session),
        StatementRule("CREATE [OR REPLACE] MACRO|FUNCTION", server),
        StatementRule("DROP MACRO|FUNCTION", session),
        StatementRule("ATTACH [DATABASE]", StatementEffect.Namespace(1)),
        StatementRule("DETACH [DATABASE]", StatementEffect.Namespace(1)),
    )

    /** Trino: creating or dropping catalogs and schemas; creating or dropping functions. */
    public val TRINO: List<StatementRule> = listOf(
        StatementRule("CREATE CATALOG", StatementEffect.Namespace(1)),
        StatementRule("DROP CATALOG", StatementEffect.Namespace(1)),
        StatementRule("CREATE SCHEMA", StatementEffect.Namespace(2)),
        StatementRule("DROP SCHEMA", StatementEffect.Namespace(2)),
        StatementRule("CREATE [OR REPLACE] FUNCTION", server),
        StatementRule("DROP FUNCTION", server),
    )

    /** Doris: creating, dropping or refreshing catalogs. */
    public val DORIS: List<StatementRule> = listOf(
        StatementRule("CREATE CATALOG", StatementEffect.Namespace(1)),
        StatementRule("DROP CATALOG", StatementEffect.Namespace(1)),
        StatementRule("REFRESH CATALOG", StatementEffect.Namespace(1, refreshDepth = 1)),
    )
}
