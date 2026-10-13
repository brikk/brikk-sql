package dev.brikk.house.sql.scope

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Ordered
import dev.brikk.house.sql.ast.PipeAggregate
import dev.brikk.house.sql.ast.PipeExtend
import dev.brikk.house.sql.ast.PipePivot
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.PipeSelect
import dev.brikk.house.sql.ast.PipeSet
import dev.brikk.house.sql.ast.PipeSetOperation
import dev.brikk.house.sql.ast.PipeUnpivot
import dev.brikk.house.sql.ast.PipeWindow
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Semicolon
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Values
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer

internal class ParsedRegion(val tokens: List<Token>, val statements: List<Expression>)

/** Parses [text] completely, or returns null: scopes need a whole statement (see [CaretCompletion] for recovery). */
internal fun parseRegion(text: String, dialect: Dialect, checkCanceled: () -> Unit): ParsedRegion? {
    val lexed = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(text, checkCanceled)
    if (lexed.failureOffset != null) return null
    val parser = dialect.parser(ErrorLevel.IMMEDIATE).apply { captureSourceRanges = true }
    val statements = try {
        parser.parse(lexed.tokens, text)
    } catch (_: ParseError) {
        return null
    } catch (_: TokenError) {
        return null
    }
    checkCanceled()
    return ParsedRegion(lexed.tokens, statements.filterNotNull().filterNot { it is Semicolon })
}

/** Lowers [statement]'s pipes the way execution does, leniently, recording what moved where. */
internal fun lowerPipes(statement: Expression, dialect: Dialect, names: SourceNames, text: String, base: Int): Pair<Expression, PipeOrigins> {
    val origins = PipeOrigins()
    for (pipe in statement.findAll<PipeQuery>().toList()) {
        val head = pipe.thisArg as? Select
        // A SELECT-first head's output, not its FROM clause, is the pipe's input.
        if (head != null && head.syntaxOrigin?.fromFirst != true && !head.isPlainStar()) {
            pipe.set("this", Subquery(args("this" to head)))
        }
        for (stage in pipe.expressionsArg.filterIsInstance<Expression>()) {
            val keyword = names.range(stage)?.let { text.substring(it.first - base, it.last + 1 - base).uppercase() }.orEmpty()
            when (stage) {
                is PipeSelect, is PipeExtend, is PipeWindow, is PipePivot, is PipeUnpivot ->
                    stage.expressionsArg.filterIsInstance<Expression>().forEach { origins.stages[it] = keyword }
                is PipeAggregate -> ((stage.expressionsArg) + (stage.args["group"] as? List<*>).orEmpty())
                    .filterIsInstance<Expression>().forEach { element ->
                        origins.stages[element] = keyword
                        if (element is Ordered) (element.thisArg as? Expression)?.let { origins.stages[it] = keyword }
                    }
                is PipeSet -> stage.expressionsArg.filterIsInstance<EQ>().forEach { assignment ->
                    val value = assignment.expressionArg as? Expression ?: return@forEach
                    origins.stages[value] = keyword
                    (assignment.thisArg as? Column)?.let { origins.setTargets[value] = it }
                }
                is PipeSetOperation -> names.range(stage)?.let { names.operatorFrom(it.first) }?.let { operator ->
                    stage.expressionsArg.filterIsInstance<Expression>().forEach { origins.operators[it] = operator }
                }
                else -> {}
            }
        }
    }
    return desugarPipes(statement, dialect, copy = false, lenient = true) to origins
}

private fun Select.isPlainStar(): Boolean {
    val star = expressionsArg.singleOrNull() as? Star ?: return false
    return star.args.values.all { it == null || it == false || (it is List<*> && it.isEmpty()) }
}

internal fun Expression.isQuery(): Boolean = this is Select || this is SetOperation || this is Subquery || this is Values

/** The queries in a statement that is not one: INSERT ... SELECT, CREATE TABLE ... AS. */
internal fun Expression.outermostQueries(): List<Expression> {
    val out = ArrayList<Expression>()
    fun visit(node: Expression) {
        if (node.isQuery()) out += node else node.iterExpressions().forEach(::visit)
    }
    visit(this)
    return out
}

internal class ScopeAnalysis(
    private val script: String,
    private val range: IntRange,
    private val dialect: Dialect,
    private val facts: RelationFacts,
    private val checkCanceled: () -> Unit,
) {
    fun analyze(): RelationScope {
        val text = script.substring(range.first, range.last + 1)
        val parsed = parseRegion(text, dialect, checkCanceled) ?: return RelationScope(emptyList(), emptyList(), null, parsed = false)
        val names = SourceNames(range.first, parsed.tokens)
        val references = ArrayList<ScopeReference>()
        val diagnostics = ArrayList<ScopeDiagnostic>()
        var output: RelationFact? = null
        for (statement in parsed.statements) {
            val (lowered, origins) = try {
                lowerPipes(statement, dialect, names, text, range.first)
            } catch (_: UnsupportedError) {
                continue
            }
            val evaluator = ScopeEvaluator(dialect, facts, names, origins, checkCanceled)
            val root = Env(emptyList(), null)
            if (lowered.isQuery()) {
                val row = evaluator.query(lowered, root)
                if (parsed.statements.size == 1) output = row.toFact()
            } else {
                lowered.outermostQueries().forEach { evaluator.query(it, root) }
            }
            references += evaluator.references
            diagnostics += evaluator.diagnostics
        }
        return RelationScope(
            references.distinctBy { Triple(it.kind, it.start, it.end) }.sortedBy { it.start },
            diagnostics.distinctBy { Triple(it.problem, it.start, it.end) }.sortedBy { it.start },
            output,
            parsed = true,
        )
    }
}

private fun Row.toFact() = RelationFact(
    columns.map { ColumnFact(it.name, it.sqlType, it.displayType, it.nullability, it.origin, it.provenance) },
    knowledge,
)

/**
 * The completion at a caret. A marker identifier goes at the caret, and when the text then does
 * not parse, recovery closes open brackets, replaces the clause around the caret with the marker,
 * and finally drops everything after it; the marker's place in the parsed tree is the position.
 */
internal class CaretCompletion(
    private val script: String,
    private val range: IntRange,
    private val offset: Int,
    private val dialect: Dialect,
    private val facts: RelationFacts,
    private val checkCanceled: () -> Unit,
) {
    private val text = script.substring(range.first, range.last + 1)
    private val caret = offset - range.first

    fun complete(): ScopeCompletion {
        var start = caret
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '$')) start--
        fun none(kind: PositionKind) = ScopeCompletion(kind, null, range.first + start, offset, emptyList())
        if (insideLiteralOrComment()) return none(PositionKind.STRING)
        val marked = text.substring(0, caret) + MARKER + text.substring(caret)
        val parsed = recover(marked) ?: return none(PositionKind.OTHER)
        val names = SourceNames(range.first, parsed.tokens)
        for (statement in parsed.statements) {
            val (lowered, origins) = try {
                lowerPipes(statement, dialect, names, marked, range.first)
            } catch (_: UnsupportedError) {
                continue
            }
            val marker = lowered.findAll<Identifier>().firstOrNull { MARKER in it.name } ?: continue
            val parent = marker.parent
            val kind = when {
                parent is Column && parent.thisArg === marker -> PositionKind.COLUMN
                parent is Column -> PositionKind.QUALIFIER
                parent is Table -> PositionKind.RELATION
                else -> PositionKind.OTHER
            }
            if (kind != PositionKind.COLUMN) return none(kind)
            val evaluator = ScopeEvaluator(dialect, facts, names, origins, checkCanceled, MARKER)
            val queries = if (lowered.isQuery()) listOf(lowered) else lowered.outermostQueries()
            // Outside any query the marker is a word being typed, not a column.
            if (queries.isEmpty()) return none(PositionKind.OTHER)
            queries.forEach { evaluator.query(it, Env(emptyList(), null)) }
            val site = evaluator.caret ?: return ScopeCompletion(kind, Knowledge.UNKNOWN, range.first + start, offset, emptyList())
            val (found, absence) = candidates(evaluator, site)
            // Pipe lowering may also project the marker itself, as an AGGREGATE grouping does.
            val columns = found.filterNot { MARKER in it.name }
            val knowledge = if (absence != Knowledge.FULL && columns.isNotEmpty()) Knowledge.PARTIAL else absence
            return ScopeCompletion(kind, knowledge, range.first + start, offset, columns.map { item(it, range.first + start) })
        }
        return none(PositionKind.OTHER)
    }

    private fun candidates(evaluator: ScopeEvaluator, site: ScopeEvaluator.CaretSite): Pair<List<ScopeColumn>, Knowledge> {
        val frame = site.frame ?: return emptyList<ScopeColumn>() to Knowledge.UNKNOWN
        val qualifier = site.qualifier
        if (qualifier == null) {
            val absence = generateSequence(frame) { it.outer }.fold(Knowledge.FULL) { knowledge, it -> worst(knowledge, it.absence) }
            return evaluator.visible(frame) to absence
        }
        evaluator.findBinding(frame, qualifier)?.let { return it.columns to it.knowledge }
        // A struct's fields are beyond the facts; an unknown qualifier names nothing, unless an open relation may hold the struct.
        val field = evaluator.unqualified(frame, qualifier)
        return emptyList<ScopeColumn>() to if (field.candidates.isNotEmpty()) Knowledge.UNKNOWN else field.knowledge
    }

    private fun item(column: ScopeColumn, start: Int): CompletionItem {
        val relation = column.source?.relation ?: column.relation
        val detail = when (column.origin) {
            ColumnOrigin.COMPUTED -> "computed column (${column.computedBy ?: "SELECT"})"
            ColumnOrigin.RELATION_CONTRACT -> "relation contract ($relation)"
            ColumnOrigin.DECLARED, ColumnOrigin.PHYSICAL_TABLE -> "column ($relation)"
        }
        return CompletionItem(column.name, CompletionKind.COLUMN, insertText(column.name), start, offset, detail,
            column.displayType ?: column.sqlType, column)
    }

    /** The name as SQL must spell it: quoted unless a bare identifier reads back as the same column. */
    private fun insertText(name: String): String {
        val bare = dev.brikk.house.sql.ast.SAFE_IDENTIFIER_RE.matches(name) &&
            (dialect.caseInsensitiveColumns || !dialect.caseSensitive(name)) &&
            Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(name).tokens.singleOrNull()?.tokenType == TokenType.VAR
        return dialect.generate(Identifier(args("this" to name, "quoted" to !bare)))
    }

    private fun insideLiteralOrComment(): Boolean {
        val lexed = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(text, checkCanceled)
        if (lexed.failureOffset != null && caret > lexed.failureOffset) return true
        val token = lexed.tokens.firstOrNull { it.start < caret && caret <= it.end }
        if (token != null && (token.tokenType == TokenType.STRING || token.tokenType.name.endsWith("_STRING"))) return true
        val previous = lexed.tokens.lastOrNull { it.end < caret }
        val gap = text.substring(previous?.end?.plus(1) ?: 0, caret)
        val line = gap.lastIndexOf("--")
        return line >= 0 && '\n' !in gap.substring(line) || gap.lastIndexOf("/*") > gap.lastIndexOf("*/")
    }

    private fun recover(marked: String): ParsedRegion? {
        parseRegion(marked, dialect, checkCanceled)?.let { return it }
        val tokens = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(marked, checkCanceled).tokens
        parseRegion(balanced(marked, tokens), dialect, checkCanceled)?.let { return it }
        blanked(marked, tokens)?.let { blank ->
            val blankTokens = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(blank, checkCanceled).tokens
            parseRegion(balanced(blank, blankTokens), dialect, checkCanceled)?.let { return it }
        }
        val cut = marked.substring(0, caret + MARKER.length)
        return parseRegion(balanced(cut, tokens.filter { it.start < cut.length }), dialect, checkCanceled)
    }

    /** [text] with its open brackets closed. */
    private fun balanced(text: String, tokens: List<Token>): String {
        val open = ArrayList<String>()
        for (token in tokens) when (token.tokenType) {
            TokenType.L_PAREN -> open += ")"
            TokenType.L_BRACKET -> open += "]"
            TokenType.R_PAREN, TokenType.R_BRACKET -> open.removeLastOrNull()
            else -> {}
        }
        return text + open.asReversed().joinToString("")
    }

    /** [marked] with the clause around the marker reduced to the marker: the scope depends on the clause, not its expression. */
    private fun blanked(marked: String, tokens: List<Token>): String? {
        val at = tokens.indexOfFirst { MARKER in it.text }.takeIf { it >= 0 } ?: return null
        var depth = 0
        var start = -1
        var index = at - 1
        while (index >= 0) {
            val token = tokens[index]
            when {
                token.tokenType == TokenType.R_PAREN || token.tokenType == TokenType.R_BRACKET -> depth++
                token.tokenType == TokenType.L_PAREN || token.tokenType == TokenType.L_BRACKET -> {
                    if (depth > 0) depth-- else if (tokens.getOrNull(index + 1)?.tokenType in QUERY_STARTS) { start = index; break }
                }
                // A stage keyword is being typed after `|>`: no clause to recover.
                depth == 0 && token.tokenType == TokenType.PIPE_GT -> return null
                depth == 0 && token.isClauseStart(tokens.getOrNull(index - 1)) -> { start = index; break }
            }
            index--
        }
        if (start < 0) return null
        depth = 0
        var end = tokens.size
        for (next in at + 1 until tokens.size) {
            val token = tokens[next]
            when {
                token.tokenType == TokenType.L_PAREN || token.tokenType == TokenType.L_BRACKET -> depth++
                token.tokenType == TokenType.R_PAREN || token.tokenType == TokenType.R_BRACKET -> if (depth == 0) { end = next; break } else depth--
                depth == 0 && token.tokenType in CLAUSE_ENDS -> { end = next; break }
            }
            if (end != tokens.size) break
        }
        val from = tokens[start].end + 1
        val to = tokens.getOrNull(end)?.start ?: marked.length
        return marked.substring(0, from) + " $MARKER " + marked.substring(to)
    }

    private fun Token.isClauseStart(previous: Token?): Boolean =
        tokenType in CLAUSE_STARTS || previous?.tokenType == TokenType.PIPE_GT

    private companion object {
        const val MARKER = "brikk_caret_7f3a"

        val QUERY_STARTS = setOf(TokenType.SELECT, TokenType.FROM, TokenType.WITH)
        val CLAUSE_STARTS = setOf(
            TokenType.SELECT, TokenType.WHERE, TokenType.ON, TokenType.HAVING, TokenType.QUALIFY,
            TokenType.GROUP_BY, TokenType.ORDER_BY, TokenType.PARTITION_BY, TokenType.USING, TokenType.FROM, TokenType.JOIN,
        )
        val CLAUSE_ENDS = setOf(
            TokenType.FROM, TokenType.WHERE, TokenType.GROUP_BY, TokenType.ORDER_BY, TokenType.HAVING,
            TokenType.QUALIFY, TokenType.LIMIT, TokenType.OFFSET, TokenType.WINDOW, TokenType.UNION,
            TokenType.INTERSECT, TokenType.EXCEPT, TokenType.PIPE_GT, TokenType.SEMICOLON, TokenType.JOIN,
        )
    }
}
