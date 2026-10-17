package dev.brikk.house.sql.parser

import dev.brikk.house.sql.dialects.Dialects
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializable projection of a pipe-stage split, matching brikk's stage-document shape:
 *
 * ```json
 * {
 *   "dialect": "bigquery",
 *   "token_count": 36,
 *   "pipe_operator_count": 3,
 *   "raw_stages": ["FROM Produce", "WHERE item != :varthing", ...]
 * }
 * ```
 */
@Serializable
data class PipeStageDocument(
    val dialect: String,
    @SerialName("token_count") val tokenCount: Int,
    @SerialName("pipe_operator_count") val pipeOperatorCount: Int,
    @SerialName("raw_stages") val rawStages: List<String>,
) {
    fun toJson(pretty: Boolean = false): String =
        (if (pretty) PRETTY_JSON else PLAIN_JSON).encodeToString(serializer(), this)
}

private val PLAIN_JSON = Json
private val PRETTY_JSON = Json { prettyPrint = true }

/**
 * One pipe stage: the raw SQL slice between `|>` operators (or before the first one),
 * with its tokens and best-effort leading-operator classification.
 *
 * [start]/[endInclusive] are UTF-16 code-unit offsets into the original Kotlin SQL
 * string covering the first through last token of the stage (surrounding whitespace
 * excluded, internal whitespace preserved).
 */
class PipeStage(
    val rawSql: String,
    val operator: String?,
    val start: Int,
    val endInclusive: Int,
    val tokens: List<Token>,
)

/** Full result of splitting a pipe query into stages. */
class PipeSplitResult(
    val dialect: String,
    val sql: String,
    val tokens: List<Token>,
    val stages: List<PipeStage>,
    val pipeOperatorCount: Int,
) {
    fun toDocument(): PipeStageDocument = PipeStageDocument(
        dialect = dialect,
        tokenCount = tokens.size,
        pipeOperatorCount = pipeOperatorCount,
        rawStages = stages.map { it.rawSql },
    )
}

/** Where run-to-stage may cut a pipe program; see [PipeStageSplitter.topLevelPrefixAt]. */
sealed interface PipeStageCut {
    /**
     * [sql] is the program through its last token in top-level [stage], a 0-based index into
     * [PipeSplitResult.stages] of [stageCount].
     */
    data class Prefix(val sql: String, val stage: Int, val stageCount: Int) : PipeStageCut

    /** Stages inside a bracketed pipeline are not top-level stages. */
    data object InsideNestedPipeline : PipeStageCut

    /** Fewer than two stages, an offset outside every stage, or untokenizable text. */
    data object Unavailable : PipeStageCut
}

/**
 * Splits a pipe-syntax SQL query (`FROM t |> WHERE ... |> ...`) into raw stages.
 *
 * Stage boundaries are `|>` (TokenType.PIPE_GT) tokens at bracket depth 0. Because
 * splitting happens on the token stream, `|>` sequences inside string literals,
 * comments, quoted identifiers, or parenthesized subpipelines never split a stage —
 * this is precisely why this is tokenizer-based rather than text-based.
 *
 * This is Phase-0 machinery: raw text slices plus tokens, no AST. Full pipe-operator
 * parsing into first-class stage nodes lives in Parser.parsePipeSyntaxQuery (PipeQuery +
 * Pipe* stage nodes in ast/PipeNodes.kt; desugaring in ast/PipeDesugar.kt).
 */
object PipeStageSplitter {

    /**
     * [config] defaults to null, in which case it is resolved from [dialect] via
     * [TokenizerConfigs.forName] (unknown dialects throw UnknownDialectException).
     * Pass an explicit [config] to override.
     */
    fun split(
        sql: String,
        dialect: String = "",
        config: TokenizerConfig? = null,
    ): PipeSplitResult {
        val tokens = Tokenizer(config ?: TokenizerConfigs.forName(dialect)).tokenize(sql)

        val stages = mutableListOf<PipeStage>()
        var pipeOperatorCount = 0
        var depth = 0
        var stageStart = 0

        fun closeStage(endExclusive: Int) {
            val stageTokens = tokens.subList(stageStart, endExclusive)
            if (stageTokens.isNotEmpty()) {
                val first = stageTokens.first()
                val last = stageTokens.last()
                stages.add(
                    PipeStage(
                        rawSql = sql.substring(first.start, last.end + 1),
                        operator = detectOperator(stageTokens),
                        start = first.start,
                        endInclusive = last.end,
                        tokens = stageTokens.toList(),
                    )
                )
            }
        }

        tokens.forEachIndexed { i, token ->
            when (token.tokenType) {
                TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> depth++
                TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> depth--
                TokenType.PIPE_GT -> if (depth == 0) {
                    pipeOperatorCount++
                    closeStage(i)
                    stageStart = i + 1
                }
                else -> {}
            }
        }
        closeStage(tokens.size)

        return PipeSplitResult(
            dialect = dialect,
            sql = sql,
            tokens = tokens,
            stages = stages,
            pipeOperatorCount = pipeOperatorCount,
        )
    }

    /**
     * The executable prefix of [sql] for running up to the top-level stage at [offset], a caret
     * position in `0..sql.length`. Stage ranges are half-open: a stage owns everything from its
     * first token up to the next stage's first token, and the last stage owns the rest of [sql].
     * A bracketed pipeline owns the positions from just before its `(` through just before its `)`.
     * A top-level `;` ends the program; positions after it are [PipeStageCut.Unavailable].
     */
    fun topLevelPrefixAt(sql: String, offset: Int, dialect: String = ""): PipeStageCut {
        if (offset !in 0..sql.length) return PipeStageCut.Unavailable
        val split = try {
            val whole = split(sql, dialect)
            when (val terminator = whole.topLevelTerminator()) {
                null -> whole
                else -> {
                    if (offset > terminator.end + 1) return PipeStageCut.Unavailable
                    split(sql.substring(0, terminator.start), dialect)
                }
            }
        } catch (_: TokenError) {
            return PipeStageCut.Unavailable
        }
        if (split.isInsideNestedPipeline(offset)) return PipeStageCut.InsideNestedPipeline
        val stages = split.stages
        val stage = stages.indexOfLast { offset >= it.start }
        if (stages.size < 2 || stage < 0) return PipeStageCut.Unavailable
        return PipeStageCut.Prefix(sql.substring(0, stages[stage].endInclusive + 1), stage, stages.size)
    }

    private fun PipeSplitResult.topLevelTerminator(): Token? {
        var depth = 0
        for (token in tokens) when (token.tokenType) {
            TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> depth++
            TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> depth--
            TokenType.SEMICOLON -> if (depth == 0) return token
            else -> {}
        }
        return null
    }

    private class Bracket(val start: Int) {
        var hasPipe = false
    }

    private fun PipeSplitResult.isInsideNestedPipeline(offset: Int): Boolean {
        val open = ArrayList<Bracket>()
        for (token in tokens) when (token.tokenType) {
            TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> open.add(Bracket(token.start))
            TokenType.PIPE_GT -> open.lastOrNull()?.hasPipe = true
            TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> open.removeLastOrNull()?.let {
                if (it.hasPipe && offset in it.start..token.end) return true
            }
            else -> {}
        }
        // An unclosed pipeline extends to the end of the text.
        return open.any { it.hasPipe && offset >= it.start }
    }

    /**
     * Best-effort classification of a stage's leading pipe operator, normalized to the
     * GoogleSQL operator names (see reference/googlesql/docs/pipe-syntax.md). JOIN-family
     * and set-operator prefixes are resolved; anything else is the first token's text
     * uppercased (e.g. `AGGREGATE`, which is not a reserved keyword and tokenizes as VAR).
     */
    private fun detectOperator(tokens: List<Token>): String? {
        val first = tokens.firstOrNull() ?: return null

        fun tokenTypeAt(i: Int): TokenType? = tokens.getOrNull(i)?.tokenType

        return when (first.tokenType) {
            TokenType.UNION, TokenType.INTERSECT, TokenType.EXCEPT -> first.text.uppercase()

            TokenType.RECURSIVE ->
                if (tokenTypeAt(1) == TokenType.UNION) "RECURSIVE UNION" else first.text.uppercase()

            // LEFT/FULL can start either a set operator (LEFT [OUTER] UNION ...) or a join
            TokenType.LEFT, TokenType.FULL -> {
                val next = tokenTypeAt(1)
                val nextNext = tokenTypeAt(2)
                when {
                    next in SET_OPS -> tokens[1].text.uppercase()
                    next == TokenType.OUTER && nextNext in SET_OPS -> tokens[2].text.uppercase()
                    else -> "JOIN"
                }
            }

            TokenType.JOIN, TokenType.CROSS, TokenType.INNER, TokenType.RIGHT,
            TokenType.NATURAL, TokenType.SEMI, TokenType.ANTI, TokenType.ASOF,
            TokenType.STRAIGHT_JOIN,
            -> "JOIN"

            else -> first.text.uppercase()
        }
    }

    private val SET_OPS = setOf(TokenType.UNION, TokenType.INTERSECT, TokenType.EXCEPT)
}

/**
 * brikk-native: the phrases that can start a pipe stage after `|>` in [dialect], from the
 * parser's own tables: transform operators (`WHERE`, `ORDER BY`, ...), joins (`JOIN`,
 * `side [OUTER] JOIN`, kind and method joins) and set operations (`ALL`/`DISTINCT`, and bare
 * where the dialect has a default). Join and set-operation phrases are omitted when a word in
 * them is not a keyword of the dialect.
 */
fun pipeStageKeywords(dialect: String): List<String> {
    val source = Dialects.forName(dialect)
    val parser = source.parser()
    val words = source.tokenizerConfig.keywords.entries
        .filter { ' ' !in it.key }
        .groupBy({ it.value }, { it.key })
        .mapValues { (type, spellings) -> spellings.firstOrNull { it == type.name } ?: spellings.first() }
    val joins = words[TokenType.JOIN]?.let { join ->
        buildList {
            add(join)
            val outer = words[TokenType.OUTER]?.takeIf { TokenType.OUTER in parser.joinKinds }
            for (side in parser.joinSides.mapNotNull(words::get)) {
                add("$side $join")
                if (outer != null) add("$side $outer $join")
            }
            for (prefix in (parser.joinKinds - TokenType.OUTER + parser.joinMethods).mapNotNull(words::get)) {
                add(if (prefix.endsWith(join)) prefix else "$prefix $join")
            }
        }
    }.orEmpty()
    val setOperations = parser.setOperations.flatMap { type ->
        val operation = words[type] ?: return@flatMap emptyList()
        listOfNotNull(
            operation.takeIf { parser.setOpDistinctByDefault(type) != null },
            words[TokenType.ALL]?.let { "$operation $it" },
            words[TokenType.DISTINCT]?.let { "$operation $it" },
        )
    }
    return parser.pipeStageTransformOperators.toList() + joins + setOperations
}

/**
 * brikk-native: the [pipeStageKeywords] of [dialect] that its engine runs as core lowers them
 * ([dev.brikk.house.sql.dialects.Dialect.pipeRunnableStages]), in their order; all of them for a
 * dialect without recorded evidence.
 */
fun pipeRunnableStageKeywords(dialect: String): List<String> {
    val parsed = pipeStageKeywords(dialect)
    val runnable = Dialects.forName(dialect).pipeRunnableStages ?: return parsed
    return parsed.filter { it in runnable }
}

/**
 * Stage phrases every engine with recorded evidence ran as lowered (Trino 483, DuckDB 1.5.6 and
 * ClickHouse 26.7 executed them; the Doris 4.1.4 FE parser accepted them). STRAIGHT_JOIN is left
 * out everywhere: no engine parses `a STRAIGHT_JOIN b`.
 */
internal val PIPE_STAGES_RUN_EVERYWHERE: Set<String> = setOf(
    "AGGREGATE", "AS", "DISTINCT", "EXTEND", "LIMIT", "OFFSET", "ORDER BY", "SELECT", "WHERE", "WINDOW",
    "JOIN", "INNER JOIN", "CROSS JOIN", "LEFT JOIN", "LEFT OUTER JOIN", "RIGHT JOIN", "RIGHT OUTER JOIN",
    "FULL JOIN", "FULL OUTER JOIN", "SEMI JOIN", "ANTI JOIN",
    "UNION ALL", "UNION DISTINCT", "INTERSECT", "INTERSECT ALL", "INTERSECT DISTINCT", "EXCEPT", "EXCEPT ALL", "EXCEPT DISTINCT",
)
