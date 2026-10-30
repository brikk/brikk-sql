package dev.brikk.house.sql.scope

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Ordered
import dev.brikk.house.sql.ast.PipeAggregate
import dev.brikk.house.sql.ast.SAFE_IDENTIFIER_RE
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
import dev.brikk.house.sql.dialects.duckdbExtensionResult
import dev.brikk.house.sql.dialects.duckdbExternalResourceResult
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.shape.catalogTableParts

/** Identifier-only dotted paths, including the Column-under-Dot representation of long names. */
internal fun Expression.scopeIdentifierParts(): List<Identifier>? {
    val pending = ArrayDeque<Expression>()
    val parts = ArrayList<Identifier>()
    pending.addLast(this)
    while (pending.isNotEmpty()) {
        when (val part = pending.removeLast()) {
            is Identifier -> parts += part
            is Column -> part.parts.asReversed().forEach { pending.addLast(it) }
            is Dot -> {
                pending.addLast(part.expressionArg as? Expression ?: return null)
                pending.addLast(part.thisArg as? Expression ?: return null)
            }
            else -> return null
        }
    }
    return parts
}

internal class ParsedRegion(val tokens: List<Token>, val statements: List<Expression>)

/** An edit replacing `[start, end)` of a text by [length] characters. */
internal class Splice(val start: Int, val end: Int, val length: Int)

/** Parses [text] completely, or returns null: scopes need a whole statement (see [CaretCompletion] for recovery). */
internal fun parseRegion(text: String, dialect: Dialect, checkCanceled: () -> Unit): ParsedRegion? {
    val lexed = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(text, checkCanceled)
    if (lexed.failureOffset != null) return null
    val parser = dialect.parser(ErrorLevel.IMMEDIATE).apply {
        captureSourceRanges = true
        this.checkCanceled = checkCanceled
    }
    val statements = try {
        parser.parse(lexed.tokens, text)
    } catch (_: ParseError) {
        return null
    } catch (_: TokenError) {
        return null
    } catch (_: StackOverflowError) {
        // The parser recurses per bracket; on a small thread stack its nesting cap may not be reached first.
        return null
    }
    checkCanceled()
    return ParsedRegion(lexed.tokens, statements.filterNotNull().filterNot { it is Semicolon })
}

/** Lowers [statement]'s pipes the way execution does, leniently, recording what moved where. */
internal fun lowerPipes(
    statement: Expression,
    dialect: Dialect,
    names: SourceNames,
    text: String,
    base: Int,
    checkCanceled: () -> Unit,
): Pair<Expression, PipeOrigins> {
    val origins = PipeOrigins()
    for (pipe in statement.findAll<PipeQuery>().toList()) {
        // Execution reads a SELECT-first head's rows, merging a later stage into the head only where
        // that is equivalent; the editor scopes the head's projection as the input it is.
        val head = pipe.thisArg as? Select
        if (head != null && head.args["from_"] != null && !head.isPlainStar()) pipe.set("this", Subquery(args("this" to head)))
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
    val lowered = desugarPipes(statement, dialect, copy = false, refused = { stage, message -> origins.refusals += stage to message }, checkCanceled)
    return lowered to origins
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
                lowerPipes(statement, dialect, names, text, range.first, checkCanceled)
            } catch (_: UnsupportedError) {
                continue
            }
            val evaluator = ScopeEvaluator(dialect, facts, names, origins, checkCanceled)
            val root = Env(emptyList(), null)
            if (lowered.isQuery()) {
                val row = evaluator.query(lowered, root)
                if (parsed.statements.size == 1) output = row.toFact()
            } else {
                evaluator.statement(lowered, root)
                if (parsed.statements.size == 1 && dialect.name == "duckdb") output = lowered.duckdbExtensionResult() ?: lowered.duckdbExternalResourceResult()
            }
            references += evaluator.references
            diagnostics += evaluator.diagnostics
            for ((stage, message) in origins.refusals) names.range(stage)?.let { keyword ->
                diagnostics += ScopeDiagnostic(ScopeProblem.PIPE_UNSUPPORTED, ScopeProblem.PIPE_UNSUPPORTED.severity,
                    "Execution refuses this stage: $message", keyword.first, keyword.last + 1)
            }
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

    /** What the caret is in: the identifier it edits (its replace range and typed prefix), or why there is none. */
    private sealed interface Edit {
        /** [marked] is the text with [inserted] at the caret: the marker, and a closing quote the name lacks. */
        class Name(val marked: String, val start: Int, val end: Int, val prefix: String, val quoted: Boolean, val inserted: Splice) : Edit
        class Elsewhere(val kind: PositionKind) : Edit
    }

    fun complete(): ScopeCompletion {
        val edit = edit()
        if (edit is Edit.Elsewhere) return ScopeCompletion(edit.kind, null, offset, offset, "", emptyList())
        edit as Edit.Name
        val start = range.first + edit.start
        val end = range.first + edit.end
        fun none(kind: PositionKind) = ScopeCompletion(kind, null, start, end, edit.prefix, emptyList())
        val (parsed, splices) = recover(edit.marked) ?: return none(PositionKind.OTHER)
        val edits = listOf(edit.inserted) + splices
        val names = SourceNames(range.first, parsed.tokens)
        for (statement in parsed.statements) {
            val (lowered, origins) = try {
                lowerPipes(statement, dialect, names, edit.marked, range.first, checkCanceled)
            } catch (_: UnsupportedError) {
                continue
            }
            val marker = lowered.findAll<Identifier>().firstOrNull { MARKER in it.name } ?: continue
            var parent = marker.parent
            // Long table names store their final components inside Dots, not directly on Table.
            while (parent is Dot) parent = parent.parent
            val markerParent = marker.parent
            val kind = when {
                parent is Table -> PositionKind.RELATION
                markerParent is Dot && markerParent.expressionArg === marker -> PositionKind.COLUMN
                parent is Column && parent.thisArg === marker -> PositionKind.COLUMN
                parent is Column -> PositionKind.QUALIFIER
                parent is Table -> PositionKind.RELATION
                else -> PositionKind.OTHER
            }
            if (kind != PositionKind.COLUMN && kind != PositionKind.RELATION) return none(kind)
            val evaluator = ScopeEvaluator(dialect, facts, names, origins, checkCanceled, MARKER)
            val root = Env(emptyList(), null)
            if (lowered.isQuery()) evaluator.query(lowered, root)
            else if (!evaluator.statement(lowered, root)) return none(PositionKind.OTHER)
            if (kind == PositionKind.RELATION) {
                val table = parent as Table
                val qualifier = table.parts.filterIsInstance<Identifier>().dropLast(1).map { it.name }
                return ScopeCompletion(kind, null, start, end, edit.prefix, relations(evaluator.caretCtes.orEmpty(), qualifier, start, end, edit.quoted))
            }
            val site = evaluator.caret ?: return ScopeCompletion(kind, Knowledge.UNKNOWN, start, end, edit.prefix, emptyList())
            val (found, absence) = candidates(evaluator, site)
            // Pipe lowering may also project the marker itself, as an AGGREGATE grouping does.
            val columns = found.filterNot { MARKER in it.name }
            val knowledge = if (absence != Knowledge.FULL && columns.isNotEmpty()) Knowledge.PARTIAL else absence
            // Declarations are found in the edited text; the items name them in the script.
            val placed = columns.map { column -> column.copy(declaration = column.declaration?.let { placed(it, edits) }) }
            return ScopeCompletion(kind, knowledge, start, end, edit.prefix, placed.map { item(it, start, end, edit.quoted) })
        }
        return none(PositionKind.OTHER)
    }

    /**
     * Places the marker at the caret and reads, from the dialect's own lexing, what it landed in:
     * a word or quoted identifier (replaced whole), a string literal, or a comment.
     */
    private fun edit(): Edit {
        val marked = text.substring(0, caret) + MARKER + text.substring(caret)
        val lexed = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(marked, checkCanceled)
        val token = lexed.tokens.firstOrNull { MARKER in it.text && it.start <= caret && caret <= it.end }
        if (token == null) {
            val failure = lexed.failureOffset?.takeIf { it <= caret } ?: return Edit.Elsewhere(PositionKind.COMMENT)
            val closer = dialect.tokenizerConfig.identifiers[marked[failure]]
                ?: return Edit.Elsewhere(if (marked.startsWith("--", failure) || marked.startsWith("/*", failure) ||
                    marked[failure] == '#') PositionKind.COMMENT else PositionKind.STRING)
            // An identifier whose closing quote is not typed yet.
            val closed = text.substring(0, caret) + MARKER + closer + text.substring(caret)
            return Edit.Name(closed, failure, caret, text.substring(failure + 1, caret), quoted = true, Splice(caret, caret, MARKER.length + 1))
        }
        if (token.tokenType == TokenType.STRING || token.tokenType.name.endsWith("_STRING")) return Edit.Elsewhere(PositionKind.STRING)
        val quoted = token.tokenType == TokenType.IDENTIFIER && marked[token.start] in dialect.tokenizerConfig.identifiers
        val end = token.end + 1 - MARKER.length
        val prefix = text.substring(if (quoted) token.start + 1 else token.start, caret)
        return Edit.Name(marked, token.start, end, prefix, quoted, Splice(caret, caret, MARKER.length))
    }

    private fun candidates(evaluator: ScopeEvaluator, site: ScopeEvaluator.CaretSite): Pair<List<ScopeColumn>, Knowledge> {
        val frame = site.frame ?: return emptyList<ScopeColumn>() to Knowledge.UNKNOWN
        val qualifier = site.qualifier
        if (qualifier.isEmpty()) {
            val absence = generateSequence(frame) { it.outer }.fold(Knowledge.FULL) { knowledge, it -> worst(knowledge, it.absence) }
            return evaluator.visible(frame) to absence
        }
        val bindings = evaluator.findBindings(frame, qualifier)
        if (bindings.isNotEmpty()) return bindings.flatMap { it.columns } to bindings.fold(Knowledge.FULL) { knowledge, it -> worst(knowledge, it.knowledge) }
        // A struct's fields are beyond the facts; an unknown qualifier names nothing, unless an open relation may hold the struct.
        val field = evaluator.unqualified(frame, qualifier.first())
        return emptyList<ScopeColumn>() to if (field.candidates.isNotEmpty()) Knowledge.UNKNOWN else field.knowledge
    }

    /**
     * What a FROM item may name: CTEs in scope and the facts' tables, slots and table functions;
     * after a qualifier, the tables under it, inserted without it.
     */
    private fun relations(ctes: List<Cte>, qualifier: List<String>, start: Int, end: Int, quoted: Boolean): List<CompletionItem> {
        fun relation(name: String, kind: RelationKind, fact: RelationFact?, source: String?, declaration: ScopeRange? = null) =
            ScopeRelation(name, kind, fact?.knowledge ?: Knowledge.FULL, source, fact?.provenance, declaration)
        val tables = facts.tables.mapNotNull { (key, fact) ->
            val parts = catalogTableParts(key).map { it.name }
            if (parts.size <= qualifier.size || parts.take(qualifier.size).zip(qualifier).any { (a, b) -> !a.equals(b, ignoreCase = true) }) return@mapNotNull null
            val name = parts.drop(qualifier.size).joinToString(".")
            CompletionItem(name, CompletionKind.RELATION, parts.drop(qualifier.size).joinToString(".") { insertText(it, quoted, columns = false) },
                start, end, "table", null, relation = relation(name, RelationKind.TABLE, fact, key))
        }
        if (qualifier.isNotEmpty()) return tables
        val visible = ctes.asReversed().distinctBy { it.name.name.lowercase() }.map { cte ->
            CompletionItem(cte.name.name, CompletionKind.RELATION, insertText(cte.name.name, quoted, columns = false), start, end, "CTE", null,
                relation = ScopeRelation(cte.name.name, RelationKind.CTE, cte.row.knowledge, cte.name.name))
        }
        // Slot and function keys are exact provider names, inserted as calls.
        val slots = facts.slots.map { (key, fact) ->
            CompletionItem(key, CompletionKind.RELATION, "$key()", start, end, "slot", null, relation = relation(key, RelationKind.SLOT, fact, key))
        }
        val calls = facts.calls.map { (key, fact) ->
            CompletionItem(key, CompletionKind.RELATION, "$key()", start, end, "table function", null, relation = relation(key, RelationKind.CALL, fact, key))
        }
        return visible + tables + slots.takeUnless { quoted }.orEmpty() + calls.takeUnless { quoted }.orEmpty()
    }

    private fun item(column: ScopeColumn, start: Int, end: Int, quoted: Boolean): CompletionItem {
        val relation = column.source?.relation ?: column.relation
        val detail = when (column.origin) {
            ColumnOrigin.COMPUTED -> "computed column (${column.computedBy ?: "SELECT"})"
            ColumnOrigin.RELATION_CONTRACT -> "relation contract ($relation)"
            ColumnOrigin.DECLARED, ColumnOrigin.PHYSICAL_TABLE -> "column ($relation)"
        }
        return CompletionItem(column.name, CompletionKind.COLUMN, insertText(column.name, quoted), start, end, detail,
            column.displayType ?: column.sqlType, column)
    }

    /** The name as SQL must spell it: quoted where it was or unless a bare identifier reads back as the same column. */
    private fun insertText(name: String, quoted: Boolean, columns: Boolean = true): String {
        val bare = !quoted && SAFE_IDENTIFIER_RE.matches(name) &&
            (columns && dialect.caseInsensitiveColumns || !dialect.caseSensitive(name)) &&
            Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(name).tokens.singleOrNull()?.tokenType == TokenType.VAR
        return dialect.generate(Identifier(args("this" to name, "quoted" to !bare)))
    }

    /** [marked] parsed, recovered if it must be, with the edits recovery made to it; brackets closed at the end move nothing. */
    private fun recover(marked: String): Pair<ParsedRegion, List<Splice>>? {
        parseRegion(marked, dialect, checkCanceled)?.let { return it to emptyList() }
        val tokens = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(marked, checkCanceled).tokens
        val at = tokens.indexOfFirst { MARKER in it.text }.takeIf { it >= 0 } ?: return null
        val markerEnd = tokens[at].end + 1
        parseRegion(balanced(marked, tokens), dialect, checkCanceled)?.let { return it to emptyList() }
        // A RENAME source needs its target before the stage parses.
        if (stageOf(tokens, at) == "RENAME") {
            val target = " AS $TARGET"
            val renamed = marked.substring(0, markerEnd) + target + marked.substring(markerEnd)
            parseRegion(balanced(renamed, Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(renamed, checkCanceled).tokens), dialect, checkCanceled)
                ?.let { return it to listOf(Splice(markerEnd, markerEnd, target.length)) }
        }
        blanked(marked, tokens, at)?.let { (blank, splice) ->
            val blankTokens = Tokenizer(dialect.tokenizerConfig).tokenizeForEditor(blank, checkCanceled).tokens
            parseRegion(balanced(blank, blankTokens), dialect, checkCanceled)?.let { return it to listOf(splice) }
        }
        val cut = marked.substring(0, markerEnd)
        return parseRegion(balanced(cut, tokens.take(at + 1)), dialect, checkCanceled)?.let { it to listOf(Splice(markerEnd, marked.length, 0)) }
    }

    /** [declaration], found in the region's text after [edits], in the script; null where an edit made it. */
    private fun placed(declaration: ScopeRange, edits: List<Splice>): ScopeRange? {
        fun unedited(position: Int): Int? {
            var at = position - range.first
            for (edit in edits.asReversed()) {
                at = when {
                    at < edit.start -> at
                    at >= edit.start + edit.length -> at - edit.length + (edit.end - edit.start)
                    else -> return null
                }
            }
            return at + range.first
        }
        val start = unedited(declaration.start) ?: return null
        val last = unedited(declaration.end - 1) ?: return null
        return ScopeRange(start, last + 1)
    }

    /** The keyword of the pipe stage holding token [at], if it is in one. */
    private fun stageOf(tokens: List<Token>, at: Int): String? {
        val pipe = (at - 1 downTo 0).firstOrNull { tokens[it].tokenType == TokenType.PIPE_GT } ?: return null
        return tokens.getOrNull(pipe + 1)?.text?.uppercase()
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
    private fun blanked(marked: String, tokens: List<Token>, at: Int): Pair<String, Splice>? {
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
        return marked.substring(0, from) + " $MARKER " + marked.substring(to) to Splice(from, to, MARKER.length + 2)
    }

    private fun Token.isClauseStart(previous: Token?): Boolean =
        tokenType in CLAUSE_STARTS || previous?.tokenType == TokenType.PIPE_GT

    private companion object {
        const val MARKER = "brikk_caret_7f3a"
        const val TARGET = "brikk_target_7f3a"

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
