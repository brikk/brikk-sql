package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Group
import dev.brikk.house.sql.ast.Having
import dev.brikk.house.sql.ast.Hint
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.Parameter
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Qualify
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Semicolon
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.SyntaxOrigin
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType

/** A source interval regenerated structurally, not a claim of full text preservation. */
data class PreservationDiagnostic(val range: SyntacticRange, val message: String)

/** Exact original occurrence and (when named) identifier-token intervals, in UTF-16 units. */
data class ParameterOccurrence(
    val name: String?,
    val style: ParamStyle,
    val range: SyntacticRange,
    val nameRange: SyntacticRange?,
)

/** Additive executable-rendering result. No best-effort generator map is relabelled as exact. */
data class SourcePreservingResult(
    val sql: String,
    val diagnostics: List<PreservationDiagnostic>,
    val unsupportedMessages: List<String>,
    val parameterOccurrences: List<ParameterOccurrence>,
    val rootKind: String,
    val isRawPassthroughStatement: Boolean,
) {
    /** Intentionally unavailable until source-copy and insertion spans can be mapped exactly. */
    val sourceMap: SourceMap? get() = null
}

/**
 * Same-dialect source-preserving executable lowering, including nested pipes and FROM-first
 * queries. Only parser-consumed syntactic intervals whose AST snapshots still match are
 * reused. Native parents with unchanged structure are patched at proved child intervals.
 * Changed portions use the existing dialect generator and report regeneration explicitly.
 *
 * [embedded] removes actual statement-separator tokens and adds a final newline so a
 * trailing line comment cannot swallow an enclosing CTE's closing parenthesis. No strings
 * or comments are searched/replaced. Source/parameter intervals always index the input.
 * Cross-dialect requests delegate to the existing source-aware translation path; they
 * never splice incompatible native SQL. AST edits must be applied to source before making
 * a new fragment. Unsupported preservation and semantic guards throw [UnsupportedError].
 */
fun SqlFragment.toSourcePreservingExecutable(
    target: String = dialect,
    embedded: Boolean = false,
): SourcePreservingResult {
    val sourceDialect = Dialects.forName(dialect)
    val targetDialect = Dialects.forName(target)
    val tokens = sourceDialect.tokenize(sql)
    val parser = sourceDialect.parser().also { it.captureSourceRanges = true }
    val statements = parser.parse(tokens, sql).filterNotNull().filterNot { it is Semicolon }
    if (statements.size != 1) throw ShapeError("Source-preserving rendering requires exactly one statement")
    val original = statements.single()
    if (!sameSyntax(ast, original)) {
        throw UnsupportedError("Source-preserving rendering cannot ignore AST edits; edit the source and construct a new SqlFragment")
    }
    val parameters = parameterOccurrences(original, tokens)
    if (sourceDialect.name != targetDialect.name) {
        val translated = toExecutable(target, trackSourceMap = false)
        return SourcePreservingResult(
            finishEmbedding(translated.sql, target, embedded),
            listOf(PreservationDiagnostic(SyntacticRange(0, sql.length), "Explicit cross-dialect translation regenerated this fragment")),
            translated.unsupportedMessages, parameters, rootKind, isRawPassthroughStatement,
        )
    }

    val renderer = PreservingRenderer(sql, tokens, targetDialect.name)
    renderer.record(original)
    if (!renderer.needsRewrite(original)) {
        return SourcePreservingResult(finishEmbedding(sql, target, embedded), emptyList(), emptyList(),
            parameters, rootKind, isRawPassthroughStatement)
    }

    // SETTINGS is query-scoped. A new CTE boundary is not evidence that moving it is safe.
    for (pipe in original.findAll<PipeQuery>()) {
        val head = pipe.thisArg as Expression
        if (head.args["settings"] != null) {
            throw UnsupportedError("Cannot prove ClickHouse SETTINGS placement across a pipe boundary; keep it in an unchanged native subquery")
        }
    }
    val lowered = desugarPipes(original, sourceDialect, copy = true)
    val generator = targetDialect.generator(sourceDialect = sourceDialect.name)
    generator.refuseUnsupportedPivots = true
    generator.sourcePreservingSql = { node ->
        renderer.reuse(node) { child ->
            val rendered = generator.sql(child)
            // Clause renderers own a separator, but an interval patch owns only its syntax.
            if (isClause(child)) rendered.removePrefix(" ") else rendered
        }?.let { if (isClause(node)) " $it" else it }
    }
    val generated = generator.generate(lowered, copy = true)
    val range = original.syntaxOrigin?.range
        ?: throw UnsupportedError("No proved statement interval for source-preserving lowering")
    val output = sql.substring(0, range.start) + generated + sql.substring(range.end)
    if (targetDialect.tokenize(output).any { it.tokenType == TokenType.PIPE_GT }) {
        throw UnsupportedError("Source-preserving lowering left an executable pipe token; refusing incomplete output")
    }
    return SourcePreservingResult(
        finishEmbedding(output, target, embedded), renderer.diagnostics.distinct(),
        generator.unsupportedMessages.toList(), parameters, rootKind, isRawPassthroughStatement,
    )
}

private class PreservingRenderer(
    val source: String,
    val tokens: List<Token>,
    val target: String,
) {
    private val snapshots = mutableMapOf<SyntaxOrigin, Expression>()
    val diagnostics = mutableListOf<PreservationDiagnostic>()

    fun record(root: Expression) {
        // One detached snapshot tree, not a deep copy per ranged node (quadratic).
        for (node in root.copy().walk()) node.syntaxOrigin?.let { snapshots[it] = node }
    }

    fun needsRewrite(node: Expression): Boolean = node.walk().any {
        it is PipeQuery || (target != "duckdb" && it is Select && it.syntaxOrigin?.fromFirst == true)
    }

    fun reuse(node: Expression, render: (Expression) -> String): String? {
        val origin = node.syntaxOrigin ?: return null
        val before = snapshots[origin] ?: return null
        val range = origin.range
        if (range.start < 0 || range.end > source.length || range.end < range.start) {
            throw UnsupportedError("Invalid source syntax interval: $range")
        }
        val first = tokens.firstOrNull { it.start >= range.start }
        // Parser-synthesized wrappers do not necessarily own their consumed source text.
        // Such intervals can diagnose regeneration, but cannot prove raw substitution.
        val synthetic = (before is Subquery && first?.tokenType != TokenType.L_PAREN) ||
            (before !is PipeQuery && hasTopLevelToken(range, TokenType.PIPE_GT))
        if (synthetic) {
            ensureHintsRetained(before, node, range)
            diagnostics.add(PreservationDiagnostic(range, "Regenerated a synthetic ${before::class.simpleName} wrapper"))
            return null
        }
        if (sameSyntax(before, node) && !needsRewrite(node)) return source.substring(range.start, range.end)

        val fromFirst = target != "duckdb" && node is Select && origin.fromFirst
        val patched = patchNativeParent(before, node, range, render)
        if (patched != null) {
            if (!fromFirst) return patched
            // Plain FROM-first is exactly SELECT * + its unchanged native clause sequence.
            // FROM ... SELECT projections requires reordering, so it uses the generator.
            if (first?.tokenType == TokenType.FROM && node.expressionsArg.singleOrNull() is Star &&
                !hasTopLevelToken(range, TokenType.SELECT)) {
                diagnostics.add(PreservationDiagnostic(range, "Inserted SELECT * to normalize FROM-first syntax"))
                return "SELECT * $patched"
            }
        }
        ensureHintsRetained(before, node, range)
        diagnostics.add(PreservationDiagnostic(range, "Regenerated ${before::class.simpleName}; unchanged descendants retain proved native syntax"))
        return null
    }

    private fun ensureHintsRetained(before: Expression, after: Expression, range: SyntacticRange) {
        for (hint in before.findAll<Hint>()) {
            val owner = hint.findAncestor(Select::class)
            val retained = owner?.syntaxOrigin?.let { origin ->
                after.walk().any { it.syntaxOrigin === origin && sameSyntax(owner, it) && !needsRewrite(it) }
            } == true
            if (!retained) {
                throw UnsupportedError("Cannot prove engine-significant hint placement while regenerating ${before::class.simpleName} at $range")
            }
        }
    }

    private fun hasTopLevelToken(range: SyntacticRange, type: TokenType): Boolean {
        var depth = 0
        for (token in tokens.filter { it.start >= range.start && it.end < range.end }) {
            when (token.tokenType) {
                TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> depth++
                TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> depth--
                else -> if (depth == 0 && token.tokenType == type) return true
            }
        }
        return false
    }

    private fun patchNativeParent(
        before: Expression, after: Expression, range: SyntacticRange, render: (Expression) -> String,
    ): String? {
        if (!sameHeader(before, after)) return null
        val replacements = mutableListOf<Pair<SyntacticRange, Expression>>()
        fun compare(left: Any?, right: Any?): Boolean {
            if (left is Expression && right is Expression) {
                if (sameSyntax(left, right) && !needsRewrite(right)) return true
                val child = left.syntaxOrigin
                if (child != null && child === right.syntaxOrigin && child.range != range &&
                    child.range.start >= range.start && child.range.end <= range.end) {
                    replacements.add(child.range to right)
                    return true
                }
                if (!sameHeader(left, right)) return false
                return compare(left.args, right.args)
            }
            if (left is Map<*, *> && right is Map<*, *>) {
                return left.keys == right.keys && left.all { (key, value) -> compare(value, right[key]) }
            }
            if (left is List<*> && right is List<*>) {
                return left.size == right.size && left.indices.all { compare(left[it], right[it]) }
            }
            return left == right
        }
        if (!compare(before.args, after.args)) return null
        val ordered = replacements.sortedBy { it.first.start }
        var cursor = range.start
        for ((child, _) in ordered) {
            if (child.start < cursor || child.end < child.start) return null
            cursor = child.end
        }
        return buildString {
            cursor = range.start
            for ((child, replacement) in ordered) {
                append(source, cursor, child.start)
                append(render(replacement))
                cursor = child.end
            }
            append(source, cursor, range.end)
        }
    }
}

private fun isClause(node: Expression): Boolean =
    node is From || node is Where || node is Group || node is Having || node is Qualify || node is Order

private fun sameHeader(left: Expression, right: Expression): Boolean {
    if (left::class != right::class || left.comments.orEmpty() != right.comments.orEmpty() ||
        left.metaOrNull != right.metaOrNull) return false
    val leftType = left.typeSlot
    val rightType = right.typeSlot
    return if (leftType != null && rightType != null) sameSyntax(leftType, rightType)
    else leftType == null && rightType == null
}

/** Compare source-affecting structure, comments and metadata, not content-normalizing AST equality. */
private fun sameSyntax(left: Expression, right: Expression): Boolean {
    val stack = ArrayDeque<Pair<Any?, Any?>>()
    stack.addLast(left to right)
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        when {
            a is Expression && b is Expression -> {
                if (a::class != b::class || a.comments.orEmpty() != b.comments.orEmpty() || a.metaOrNull != b.metaOrNull) return false
                stack.addLast(a.args to b.args)
                stack.addLast(a.typeSlot to b.typeSlot)
            }
            a is Map<*, *> && b is Map<*, *> -> {
                if (a.keys != b.keys) return false
                for ((key, value) in a) stack.addLast(value to b[key])
            }
            a is List<*> && b is List<*> -> {
                if (a.size != b.size) return false
                for (index in a.indices) stack.addLast(a[index] to b[index])
            }
            a != b -> return false
        }
    }
    return true
}

internal fun parameterOccurrences(root: Expression, tokens: List<Token>): List<ParameterOccurrence> =
    root.walk().filter { it is Parameter || it is Placeholder }.map { node ->
        val origin = node.syntaxOrigin
            ?: throw UnsupportedError("Cannot prove the syntactic interval of parameter '${node.name}'")
        val parts = tokens.filter { it.start >= origin.range.start && it.end < origin.range.end }
        if (parts.isEmpty()) throw UnsupportedError("Parameter interval has no tokens")
        val name = (if (node is Placeholder) node.text("this") else node.name).ifEmpty { null }
        val style = when {
            node is Parameter -> ParamStyle.NAMED_AT
            name != null -> ParamStyle.NAMED_COLON
            else -> ParamStyle.POSITIONAL
        }
        val nameToken = when (parts.first().tokenType) {
            TokenType.COLON, TokenType.PARAMETER, TokenType.L_BRACE -> parts.getOrNull(1)
            TokenType.MOD -> parts.getOrNull(2)
            else -> null
        }?.takeIf { name != null && it.text == name }
        if (name != null && nameToken == null) throw UnsupportedError("Cannot prove the identifier interval of parameter '$name'")
        ParameterOccurrence(name, style, SyntacticRange(parts.first().start, parts.last().end + 1),
            nameToken?.let { SyntacticRange(it.start, it.end + 1) })
    // Parser normalization may copy a parameter into more than one AST position;
    // occurrences describe authored syntax, not duplicated normalized nodes.
    }.distinctBy { it.range }.sortedBy { it.range.start }.toList()

private fun finishEmbedding(sql: String, dialect: String, embedded: Boolean): String {
    if (!embedded) return sql
    val separators = Dialects.forName(dialect).tokenize(sql).filter { it.tokenType == TokenType.SEMICOLON }
    val output = buildString {
        var cursor = 0
        for (token in separators) {
            append(sql, cursor, token.start)
            cursor = token.end + 1
        }
        append(sql, cursor, sql.length)
    }
    return if (output.endsWith('\n')) output else "$output\n"
}
