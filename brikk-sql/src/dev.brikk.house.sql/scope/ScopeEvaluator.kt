package dev.brikk.house.sql.scope

import dev.brikk.house.sql.ast.AggFunc
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Count
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DenseRank
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Except
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.In
import dev.brikk.house.sql.ast.Intersect
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Lambda
import dev.brikk.house.sql.ast.Lateral
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Tuple
import dev.brikk.house.sql.ast.Ntile
import dev.brikk.house.sql.ast.Paren
import dev.brikk.house.sql.ast.Pivot
import dev.brikk.house.sql.ast.Rank
import dev.brikk.house.sql.ast.RowNumber
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.Values
import dev.brikk.house.sql.ast.Update
import dev.brikk.house.sql.ast.Merge
import dev.brikk.house.sql.ast.Insert
import dev.brikk.house.sql.ast.When
import dev.brikk.house.sql.ast.Whens
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.Window
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.ast.outputName
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.NormalizationStrategy
import dev.brikk.house.sql.dialects.UsingColumnOrder
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.dataTypeFromStr
import dev.brikk.house.sql.optimizer.expressionNullability
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.catalogTableParts
import java.util.IdentityHashMap

/** A query's result row; output columns have no relation until a FROM clause names them. */
internal class Row(val columns: List<ScopeColumn>, val knowledge: Knowledge)

/** A relation in a FROM clause. [knowledge] says what an unlisted name means (see [Frame.absence]). */
internal class Binding(
    val name: String,
    val qualifiable: Boolean,
    val relation: ScopeRelation,
    val columns: List<ScopeColumn>,
    /** The stored names a qualifier may spell it with, last part last: `db.t` for an unaliased table. */
    val path: List<String> = listOf(name),
) {
    val knowledge: Knowledge get() = relation.knowledge
}

/**
 * The names a clause can see: its FROM relations, merged USING keys, the projection aliases
 * the clause may use ([aliasesFirst] in ORDER BY, after the relations elsewhere), and the
 * enclosing query's frame for correlated references.
 */
internal class Frame(
    val bindings: List<Binding>,
    val outer: Frame?,
    val merged: List<ScopeColumn> = emptyList(),
    val aliases: List<ScopeColumn> = emptyList(),
    val aliasesFirst: Boolean = false,
) {
    /** Whether a name absent here is proven absent: UNKNOWN when any relation is unknown, PARTIAL when any is partial. */
    val absence: Knowledge get() = bindings.fold(Knowledge.FULL) { knowledge, binding -> worst(knowledge, binding.knowledge) }

    fun with(aliases: List<ScopeColumn>, first: Boolean) = Frame(bindings, outer, merged, aliases, first)
}

/** A CTE visible by [name]; [synthetic] ones come from pipe lowering and have no name in the text. */
internal class Cte(val name: Identifier, val row: Row, val synthetic: Boolean)

internal class Env(val ctes: List<Cte>, val outer: Frame?) {
    fun withOuter(frame: Frame?) = Env(ctes, frame)
}

/** Where pipe lowering moved a stage's own nodes, to name what computes a column and to find SET targets. */
internal class PipeOrigins {
    val stages = IdentityHashMap<Expression, String>()
    val setTargets = IdentityHashMap<Expression, Column>()
    val operators = IdentityHashMap<Expression, Pair<IntRange, String>>()
    val refusals = ArrayList<Pair<Expression, String>>()
}

/**
 * Evaluates one lowered statement: rows bottom-up, every name resolved where it is written.
 * All offsets are script offsets ([SourceNames]).
 */
internal class ScopeEvaluator(
    private val dialect: Dialect,
    private val facts: RelationFacts,
    private val names: SourceNames,
    private val origins: PipeOrigins,
    private val checkCanceled: () -> Unit,
    private val caretMarker: String? = null,
) {
    val references = ArrayList<ScopeReference>()
    val diagnostics = ArrayList<ScopeDiagnostic>()
    private val resolved = IdentityHashMap<Expression, ScopeColumn>()
    private val catalogPaths = HashMap<String, List<String>>()
    private var resolutions = 0

    /** The frame and qualifier at the caret marker, when completing. */
    var caret: CaretSite? = null
        private set

    class CaretSite(val frame: Frame?, val qualifier: List<Identifier>)

    /** The CTEs visible where the caret marker names a FROM item, when completing one. */
    var caretCtes: List<Cte>? = null
        private set

    /** Non-query statements normally contribute only their queries; DuckDB UPDATE/MERGE also have row scopes. */
    fun statement(node: Expression, env: Env): Boolean {
        if (node is Update && dialect.name == "duckdb") {
            update(node, env)
            return true
        }
        if (node is Merge && dialect.name == "duckdb") {
            merge(node, env)
            return true
        }
        val queries = node.outermostQueries()
        queries.forEach { query(it, env) }
        return queries.isNotEmpty()
    }

    private fun update(node: Update, env: Env) {
        checkCanceled()
        val scoped = withClause(node, env)
        val target = node.thisArg as? Table ?: return
        // A write target is a physical table, never a same-named CTE or relation slot.
        val physical = Env(emptyList(), env.outer)
        val targets = source(target, physical, Frame(emptyList(), env.outer))
        val targetFrame = Frame(targets, env.outer)
        // DuckDB does not expose the target inside FROM joins or lateral source queries.
        val sources = from(node, scoped)
        val inputs = Frame(targets + sources.bindings, env.outer, sources.merged)
        assignments(node, targetFrame, inputs, scoped)
        expression(node.args["where"] as? Expression, inputs, scoped)
        // RETURNING reads the updated target, not the FROM sources. This does not claim
        // a statement result shape or assignment lineage; those need a separate DML contract.
        expression(node.args["returning"] as? Expression, targetFrame, scoped)
    }

    private fun assignments(node: Update, targets: Frame, inputs: Frame, env: Env) {
        for (assignment in node.expressionsArg.filterIsInstance<Expression>()) {
            checkCanceled()
            if (assignment is EQ) {
                expression(assignment.thisArg as? Expression, targets, env)
                expression(assignment.expressionArg as? Expression, inputs, env)
            } else expression(assignment, inputs, env)
        }
    }

    private fun merge(node: Merge, env: Env) {
        checkCanceled()
        val scoped = withClause(node, env)
        val target = node.thisArg as? Table ?: return
        val targets = source(target, Env(emptyList(), env.outer), Frame(emptyList(), env.outer))
        val targetFrame = Frame(targets, env.outer)
        val sources = (node.args["using"] as? Expression)?.let { source(it, scoped, Frame(emptyList(), env.outer)) }.orEmpty()
        val sourceFrame = Frame(sources, env.outer)
        val keys = (node.args["using_cond"] as? List<*>).orEmpty().filterIsInstance<Expression>()
        // Reuse the JOIN USING name contract, with copies so the AST's parent links stay intact.
        val common = if (keys.isEmpty()) emptyList() else usingKeys(Join(args("using" to keys.map { it.copy() })), targets, emptyList(), sources)
            .map { merge(it, "") }
        val both = Frame(targets + sources, env.outer, common)
        expression(node.args["on"] as? Expression, both, scoped)
        for (branch in (node.args["whens"] as? Whens)?.expressionsArg.orEmpty().filterIsInstance<When>()) {
            checkCanceled()
            val inputs = when {
                branch.args["matched"] == true -> both
                branch.args["source"] == true -> targetFrame
                else -> sourceFrame
            }
            expression(branch.args["condition"] as? Expression, inputs, scoped)
            when (val action = branch.args["then"]) {
                is Update -> assignments(action, targetFrame, inputs, scoped)
                is Insert -> {
                    expression(action.thisArg as? Expression, targetFrame, scoped)
                    expression(action.expressionArg as? Expression, inputs, scoped)
                }
                is Expression -> expression(action, inputs, scoped)
            }
        }
        // The engine's actual target column wins over the pseudo-column when names collide.
        // An incomplete inventory guarantees the name via fallback, not an unshadowed VARCHAR type.
        val shadowed = targets.any { binding -> binding.columns.any { same(it.name, "merge_action") } }
        val action = when {
            shadowed -> emptyList()
            targets.all { it.knowledge == Knowledge.FULL } -> listOf(computed("merge_action", Nullability.NON_NULL, "MERGE", "VARCHAR"))
            else -> listOf(ScopeColumn("merge_action", "", ColumnOrigin.RELATION_CONTRACT, Nullability.UNKNOWN))
        }
        val returning = targetFrame.with(action, first = false)
        expression(node.args["returning"] as? Expression, returning, scoped)
    }

    fun query(node: Expression, env: Env, context: String = "SELECT"): Row {
        // Recursion follows query nesting only, which the parser caps at its bracket depth.
        checkCanceled()
        val scoped = withClause(node, env)
        return when (node) {
            is Select -> select(node, scoped, context)
            is SetOperation -> setOperation(node, scoped)
            is Subquery -> {
                val row = query(node.thisArg as? Expression ?: return Row(emptyList(), Knowledge.UNKNOWN), scoped, context)
                orderBy(node, Frame(listOf(anonymous(row)), env.outer), scoped)
                row
            }
            is Values -> values(node, scoped)
            else -> {
                expression(node, env.outer, scoped)
                Row(emptyList(), Knowledge.UNKNOWN)
            }
        }
    }

    private fun withClause(node: Expression, env: Env): Env {
        val with = node.args["with_"] as? With ?: return env
        var ctes = env.ctes
        for (cte in with.expressionsArg.filterIsInstance<CTE>()) {
            val alias = cte.args["alias"] as? TableAlias ?: continue
            val name = alias.thisArg as? Identifier ?: continue
            val body = cte.thisArg as? Expression ?: continue
            val synthetic = !names.positioned(name)
            val visible = if (with.recursive) ctes + Cte(name, Row(emptyList(), Knowledge.UNKNOWN), synthetic) else ctes
            val row = query(body, Env(visible, env.outer), if (synthetic) "SELECT" else name.name)
            val defined = Row(renamedColumns(row.columns, alias), row.knowledge)
            if (!synthetic) reference(ReferenceKind.TABLE, name, Resolution.RESOLVED, defined.knowledge,
                ScopeRelation(stored(name), RelationKind.CTE, defined.knowledge, stored(name), declaration = declared(name)))
            ctes = ctes + Cte(name, defined, synthetic)
        }
        return Env(ctes, env.outer)
    }

    private fun select(select: Select, env: Env, context: String): Row {
        val from = from(select, env)
        val projections = ArrayList<ScopeColumn>()
        // Named projections a later clause may use; star output is already visible through FROM.
        val aliases = ArrayList<ScopeColumn>()
        val groupable = ArrayList<ScopeColumn>()
        var open = Knowledge.FULL
        for (projection in select.expressionsArg.filterIsInstance<Expression>()) {
            checkCanceled()
            val star = projection as? Star ?: (projection as? Column)?.thisArg as? Star
            if (star != null) {
                val bindings = if (projection is Column) qualifiedStar(projection, from) else from.bindings
                val (columns, knowledge) = if (projection is Column && bindings.isEmpty()) emptyList<ScopeColumn>() to Knowledge.UNKNOWN
                    else star(star, bindings, if (projection is Column) emptyList() else from.merged, from, env, context)
                projections += columns
                open = worst(open, knowledge)
            } else {
                // A lateral alias names an earlier item of the same list, after input columns.
                expression(projection, if (dialect.lateralColumnAliases) from.with(aliases.toList(), first = false) else from, env)
                val column = projected(projection, context)
                projections += column
                if (column.name.isNotEmpty()) {
                    aliases += column
                    if (!aggregated(projection)) groupable += column
                }
            }
        }
        expression(select.args["where"] as? Expression, from.with(if (dialect.whereProjectionAliases) groupable else emptyList(), first = false), env)
        expression(select.args["group"] as? Expression, from.with(if (dialect.groupByProjectionAliases) groupable else emptyList(), first = false), env)
        expression(select.args["having"] as? Expression, from.with(if (dialect.havingProjectionAliases) aliases else emptyList(), first = false), env)
        expression(select.args["qualify"] as? Expression, from.with(aliases, first = false), env)
        (select.args["windows"] as? List<*>)?.filterIsInstance<Expression>()?.forEach { expression(it, from, env) }
        (select.args["distinct"] as? Expression)?.let { expression(it, from, env) }
        orderBy(select, from, env, aliases)
        return Row(projections, open)
    }

    /** ORDER BY sees [frame] and, before it, the projection [aliases]. */
    private fun orderBy(node: Expression, frame: Frame, env: Env, aliases: List<ScopeColumn> = emptyList()) {
        expression(node.args["order"] as? Expression, frame.with(aliases, first = true), env)
    }

    // ------------------------------------------------------------------ FROM

    private class Joined(val bindings: List<Binding>, val merged: List<ScopeColumn>)

    private fun from(node: Expression, env: Env): Frame {
        val first = (node.args["from_"] as? From)?.thisArg as? Expression
            ?: return Frame(emptyList(), env.outer)
        var bindings = source(first, env, Frame(emptyList(), env.outer))
        var merged = emptyList<ScopeColumn>()
        for (join in (node.args["joins"] as? List<*>).orEmpty().filterIsInstance<Join>()) {
            val right = source(join.thisArg as? Expression ?: continue, env, Frame(bindings, env.outer, merged))
            val joined = joinRows(join, bindings, merged, right, env)
            bindings = joined.bindings
            merged = joined.merged
        }
        (node.args["laterals"] as? List<*>)?.filterIsInstance<Expression>()?.forEach {
            bindings = bindings + source(it, env, Frame(bindings, env.outer, merged))
        }
        return Frame(bindings, env.outer, merged)
    }

    private fun joinRows(join: Join, left: List<Binding>, leftMerged: List<ScopeColumn>, right: List<Binding>, env: Env): Joined {
        val condition = Frame(left + right, env.outer, leftMerged)
        expression(join.args["on"] as? Expression, condition, env)
        // Ranking reads candidate rows before an outer join adds NULL-extended outputs.
        expression(join.args["nearest"] as? Expression, condition, env)
        val side = join.side
        val semi = join.isSemiOrAntiJoin
        val keys = usingKeys(join, left, leftMerged, right)
        val nullLeft = !semi && (side == "RIGHT" || side == "FULL")
        val nullRight = !semi && (side == "LEFT" || side == "FULL")
        val outLeft = if (nullLeft) left.map(::nullable) else left
        val outRight = if (nullRight) right.map(::nullable) else right
        val keyNames = keys.map { it.name }
        val keptMerged = (if (nullLeft) leftMerged.map { it.copy(nullability = Nullability.NULLABLE) } else leftMerged)
            .filterNot { old -> keyNames.any { same(it, old.name, columns = true) } }
        return when {
            semi && side == "RIGHT" -> Joined(right, emptyList())
            semi -> Joined(left, leftMerged)
            else -> Joined(outLeft + outRight, keptMerged + keys.map { merge(it, side) })
        }
    }

    /** A join key; [declaration] is its name in the join's USING list, none for a NATURAL join's. */
    private class Key(val name: String, val left: ScopeColumn?, val right: ScopeColumn?, val declaration: ScopeRange? = null)

    private fun usingKeys(join: Join, left: List<Binding>, leftMerged: List<ScopeColumn>, right: List<Binding>): List<Key> {
        val leftFrame = Frame(left, null, leftMerged)
        val rightFrame = Frame(right, null)
        val using = (join.args["using"] as? List<*>).orEmpty().filterIsInstance<Expression>()
        if (using.isNotEmpty()) {
            return using.mapNotNull { key ->
                val identifier = (key as? Column)?.thisArg as? Identifier ?: key as? Identifier ?: return@mapNotNull null
                val l = unqualified(leftFrame, identifier)
                val r = unqualified(rightFrame, identifier)
                val lookup = when {
                    l.single != null && r.single != null ->
                        Lookup(listOf(merge(Key(stored(identifier), l.single, r.single, declared(identifier)), join.side)), Knowledge.FULL)
                    l.candidates.isEmpty() -> l
                    r.candidates.isEmpty() -> r
                    else -> Lookup(l.candidates + r.candidates, Knowledge.FULL)
                }
                record(identifier, null, lookup)
                Key(stored(identifier), l.single, r.single, declared(identifier))
            }
        }
        if (join.method != "NATURAL") return emptyList()
        val leftColumns = leftMerged + left.flatMap { it.columns }.filterNot { c -> leftMerged.any { same(it.name, c.name) } }
        return leftColumns.mapNotNull { l ->
            val r = right.flatMap { it.columns }.filter { same(it.name, l.name) }
            r.singleOrNull()?.let { Key(l.name, l, it) }
        }.distinctBy { fold(it.name) }
    }

    private fun merge(key: Key, side: String): ScopeColumn {
        val left = key.left
        val right = key.right
        val kept = when {
            left == null -> right
            right == null -> left
            side == "RIGHT" -> right
            side == "FULL" -> null
            else -> left
        }
        if (kept != null) return kept.copy(relation = "", relationKind = null)
        // COALESCE(left, right) over a FULL join: each side alone supplies the rows the other lacks.
        val nullability = when {
            left?.nullability == Nullability.NULLABLE || right?.nullability == Nullability.NULLABLE -> Nullability.NULLABLE
            left?.nullability == Nullability.NON_NULL && right?.nullability == Nullability.NON_NULL -> Nullability.NON_NULL
            else -> Nullability.UNKNOWN
        }
        if (left != null && left.source != null && left.source == right?.source) return left.copy(relation = "", relationKind = null, nullability = nullability)
        // One key in two relations: what both sides say about it still holds.
        return computed(key.name, nullability, "USING", left?.sqlType?.takeIf { it == right?.sqlType })
            .copy(displayType = left?.displayType?.takeIf { it == right?.displayType }, declaration = key.declaration)
    }

    /** The relations [node] contributes to a FROM clause; [lateral] is what it may correlate with. */
    private fun source(node: Expression, env: Env, lateral: Frame): List<Binding> {
        val base: Binding = when (node) {
            is Table -> table(node, env, lateral)
            is TableAlias -> {
                val name = node.thisArg as? Identifier
                cte(name, env)?.let { (cte, row) -> bound(row, name, cte.synthetic, RelationKind.CTE, cte.name.name, declared(cte.name)) }
                    ?: unknownRelation(node.name, RelationKind.OTHER)
            }
            is Subquery -> {
                val label = (node.args["alias"] as? TableAlias)?.thisArg as? Identifier
                val row = query(node.thisArg as? Expression ?: return emptyList(), env, label?.name ?: "SELECT")
                orderBy(node, Frame(listOf(anonymous(row)), env.outer), env)
                bound(row, null, synthetic = true, kind = RelationKind.SUBQUERY, source = null)
            }
            is Values -> bound(values(node, env.withOuter(lateral)), null, synthetic = true, kind = RelationKind.VALUES, source = null)
            is Lateral -> {
                val inner = (node.thisArg as? Subquery)?.thisArg as? Expression ?: node.thisArg as? Expression
                if (inner != null && inner.isQuery()) bound(query(inner, env.withOuter(lateral)), null, synthetic = true, kind = RelationKind.SUBQUERY, source = null)
                else opaque(node, lateral, env)
            }
            else -> opaque(node, lateral, env)
        }
        val alias = node.args["alias"] as? TableAlias
        val labeled = if (alias != null && node !is TableAlias) relabel(base, alias) else base
        val pivoted = (node.args["pivots"] as? List<*>).orEmpty().filterIsInstance<Pivot>()
            .fold(labeled) { binding, pivot -> pivot(binding, pivot, env) }
        var bindings = listOf(pivoted)
        var merged = emptyList<ScopeColumn>()
        if (node is Table || node is Subquery) for (join in (node.args["joins"] as? List<*>).orEmpty().filterIsInstance<Join>()) {
            val right = source(join.thisArg as? Expression ?: continue, env, Frame(bindings, env.outer, merged))
            val joined = joinRows(join, bindings, merged, right, env)
            bindings = joined.bindings
            merged = joined.merged
        }
        return bindings
    }

    /** A FROM item whose columns only its alias names: UNNEST, an unmodelled table expression. */
    private fun opaque(node: Expression, lateral: Frame, env: Env): Binding {
        expression(node, lateral, env)
        val columns = (node.args["alias"] as? TableAlias)?.columns.orEmpty().filterIsInstance<Identifier>()
        val row = Row(columns.map { computed(stored(it), Nullability.UNKNOWN, node.key.uppercase()).copy(declaration = declared(it)) }, if (columns.isEmpty()) Knowledge.UNKNOWN else Knowledge.FULL)
        return bound(row, null, synthetic = true, kind = RelationKind.FUNCTION, source = null)
    }

    private fun table(node: Table, env: Env, lateral: Frame): Binding {
        val function = node.thisArg as? Anonymous
        if (function != null) return tableFunction(node, function, function.name, function.expressionsArg.filterIsInstance<Expression>(), env, lateral)
        // A function core parses as its own node (ClickHouse's format(...)): its arguments in order.
        val call = (node.thisArg as? Expression)?.takeIf { it is Func }
        if (call != null) return tableFunction(node, call, call.key, listOfNotNull(call.thisArg as? Expression) + call.expressionsArg.filterIsInstance<Expression>(), env, lateral)
        val pathParts = node.parts
        if (pathParts.any { it !is Identifier }) return unknownRelation(node.name, RelationKind.OTHER)
        val parts = pathParts.filterIsInstance<Identifier>()
        val name = parts.lastOrNull() ?: return unknownRelation(node.name, RelationKind.OTHER)
        if (caretMarker != null && caretMarker in name.name && caretCtes == null) caretCtes = env.ctes.filterNot { it.synthetic }
        if (node.db.isEmpty() && node.catalog.isEmpty()) {
            cte(name, env)?.let { (cte, row) ->
                val relation = bound(row, name, cte.synthetic, RelationKind.CTE, cte.name.name, declared(cte.name))
                if (!cte.synthetic) reference(ReferenceKind.TABLE, name, Resolution.RESOLVED, row.knowledge, relation = relation.relation)
                return relation
            }
        }
        val matches = facts.tables.entries.filter { (key, _) -> sameTable(key, parts) }
        val match = matches.singleOrNull()
            ?: facts.searchPath.firstNotNullOfOrNull { namespace -> matches.singleOrNull { (key, _) -> inNamespace(key, parts.size, namespace) } }
        if (match == null && matches.size > 1) {
            // Several tables answer to the name and the search path picks none: which one is unknown.
            reference(ReferenceKind.TABLE, name, Resolution.AMBIGUOUS, Knowledge.UNKNOWN, start = parts.firstOrNull())
            val label = parts.joinToString(".", transform = ::stored)
            return Binding(label, true, ScopeRelation(label, RelationKind.TABLE, Knowledge.UNKNOWN), emptyList(), parts.map(::stored))
        }
        if (match == null) {
            val knowledge = Knowledge.UNKNOWN
            reference(ReferenceKind.TABLE, name, Resolution.UNRESOLVED, knowledge, start = parts.firstOrNull())
            if (facts.catalog == CatalogKnowledge.CLOSED) {
                diagnose(ScopeProblem.UNKNOWN_TABLE, "Unknown table '${parts.joinToString(".") { it.name }}'", parts.firstOrNull() ?: name, name)
            }
            val label = parts.joinToString(".", transform = ::stored)
            return Binding(label, true, ScopeRelation(label, RelationKind.TABLE, knowledge), emptyList(), parts.map(::stored))
        }
        val (key, fact) = match
        // An unaliased table is visible by the name as written, qualified or not.
        val label = parts.joinToString(".", transform = ::stored)
        val relation = ScopeRelation(label, RelationKind.TABLE, fact.knowledge, key, fact.provenance)
        reference(ReferenceKind.TABLE, name, Resolution.RESOLVED, fact.knowledge, relation = relation, start = parts.firstOrNull())
        return Binding(label, true, relation, fact.columns.map { it.visible(key, label, RelationKind.TABLE) }, path(parts, key))
    }

    private fun tableFunction(node: Table, function: Expression, name: String, arguments: List<Expression>, env: Env, lateral: Frame): Binding {
        val rows = arguments.map { argument ->
            val query = (argument as? Subquery)?.thisArg as? Expression ?: argument.takeIf { it is Select || it is SetOperation }
            when {
                query != null -> query(query, env.withOuter(env.outer))
                // A named argument (`structure = '...'`, `"uri" = "..."`): its name is no column.
                argument is EQ && argument.thisArg is Column && (argument.thisArg as Column).table.isEmpty() -> {
                    expression(argument.args["expression"] as? Expression, lateral, env)
                    null
                }
                else -> {
                    expression(argument, lateral, env)
                    null
                }
            }
        }
        val qualified = node.db.isNotEmpty() || node.catalog.isNotEmpty()
        val (kind, key, fact) = when {
            !qualified && arguments.isEmpty() && name in facts.slots -> Triple(RelationKind.SLOT, name, facts.slots.getValue(name))
            !qualified && name in facts.calls -> Triple(RelationKind.CALL, name, facts.calls.getValue(name))
            else -> Triple(RelationKind.FUNCTION, null, null)
        }
        val declared = if (fact == null && dialect.tableFunctionArgumentsDeclareColumns) declaredColumns(name, arguments, rows) else null
        val relation = ScopeRelation(name, kind, fact?.knowledge ?: if (declared != null) Knowledge.FULL else Knowledge.UNKNOWN, key, fact?.provenance)
        names.range(function)?.let { span ->
            references += ScopeReference(ReferenceKind.TABLE, name, span.first, span.last + 1,
                if (fact != null) Resolution.RESOLVED else Resolution.UNRESOLVED, relation.knowledge, relation = relation.takeIf { fact != null })
        }
        val label = stored(name, quoted = false)
        val columns = declared ?: fact?.columns.orEmpty().map { it.visible(name, label, kind) }
        return Binding(label, true, relation.copy(name = label), columns)
    }

    /**
     * The columns a call's arguments declare, or null: a query's for `view(query)`, else those of its
     * structure string, named `structure` or the first string argument that is a list of typed columns.
     */
    private fun declaredColumns(name: String, arguments: List<Expression>, rows: List<Row?>): List<ScopeColumn>? {
        val label = stored(name, quoted = false)
        if (name.equals("view", ignoreCase = true)) {
            return rows.singleOrNull()?.takeIf { it.knowledge == Knowledge.FULL }?.columns?.map { it.through(label, RelationKind.FUNCTION) }
        }
        fun text(argument: Expression?) = (argument as? Literal)?.takeIf { it.isString }?.name
        val named = arguments.filterIsInstance<EQ>().firstOrNull { ((it.thisArg as? Column)?.name).equals("structure", ignoreCase = true) }
        val structures = if (named != null) listOfNotNull(text(named.args["expression"] as? Expression)) else arguments.mapNotNull(::text)
        val columns = structures.firstNotNullOfOrNull { TableFunctionStructure.parse(it, dialect.name) } ?: return null
        return columns.map { it.visible(name, label, RelationKind.FUNCTION) }
    }

    private fun cte(name: Identifier?, env: Env): Pair<Cte, Row>? {
        if (name == null) return null
        val cte = env.ctes.lastOrNull { same(name, stored(it.name), columns = false) } ?: return null
        return cte to cte.row
    }

    private fun bound(
        row: Row,
        label: Identifier?,
        synthetic: Boolean,
        kind: RelationKind,
        source: String?,
        declaration: ScopeRange? = null,
    ): Binding {
        val name = if (synthetic || label == null) "" else stored(label)
        val columns = deduplicated(row.columns).map { it.through(name, kind) }
        return Binding(name, name.isNotEmpty(), ScopeRelation(name, kind, row.knowledge, source, declaration = declaration), columns)
    }

    private fun anonymous(row: Row) = Binding("", false, ScopeRelation("", RelationKind.OTHER, row.knowledge), row.columns)

    private fun relabel(binding: Binding, alias: TableAlias): Binding {
        val label = alias.thisArg as? Identifier
        val name = label?.let(::stored) ?: binding.name
        val qualifiable = if (label != null) names.positioned(label) else binding.qualifiable
        val columns = renamedColumns(binding.columns, alias).map { it.through(if (qualifiable) name else "", binding.relation.kind) }
        val declaration = label?.let(::declared) ?: binding.relation.declaration
        return Binding(name, qualifiable, binding.relation.copy(name = name, declaration = declaration), columns)
    }

    private fun renamedColumns(columns: List<ScopeColumn>, alias: TableAlias): List<ScopeColumn> {
        val names = alias.columns.filterIsInstance<Identifier>()
        return columns.mapIndexed { index, column ->
            names.getOrNull(index)?.let { column.copy(name = stored(it), declaration = declared(it)) } ?: column
        }
    }

    private fun unknownRelation(name: String, kind: RelationKind) =
        Binding(name, name.isNotEmpty(), ScopeRelation(name, kind, Knowledge.UNKNOWN), emptyList())

    private fun nullable(binding: Binding) =
        Binding(binding.name, binding.qualifiable, binding.relation, binding.columns.map { it.copy(nullability = Nullability.NULLABLE) }, binding.path)

    /** DuckDB renames repeated output names of a derived table: the second `id` becomes `id_1`. */
    private fun deduplicated(columns: List<ScopeColumn>): List<ScopeColumn> {
        if (!dialect.deduplicatesDerivedColumnNames) return columns
        val taken = columns.mapTo(HashSet()) { fold(it.name) }
        val seen = HashSet<String>()
        // The next suffix to try per name, so many repeats of one name stay linear.
        val next = HashMap<String, Int>()
        return columns.map { column ->
            val key = fold(column.name)
            if (column.name.isEmpty() || seen.add(key)) return@map column
            var suffix = next[key] ?: 1
            while ("${key}_$suffix" in taken) suffix++
            next[key] = suffix + 1
            taken += "${key}_$suffix"
            column.copy(name = "${column.name}_$suffix")
        }
    }

    // ------------------------------------------------------------------ projections

    private fun star(star: Star, bindings: List<Binding>, merged: List<ScopeColumn>, frame: Frame, env: Env, context: String): Pair<List<ScopeColumn>, Knowledge> {
        var columns = expandStar(bindings, merged).map { it.copy(relation = "", relationKind = null) }
        val knowledge = Frame(bindings, null).absence
        // Modifiers name input columns: references resolve where the star reads.
        val pool = Frame(bindings, null, merged)
        for (except in (star.args["except_"] as? List<*>).orEmpty().filterIsInstance<Expression>()) {
            val identifier = (except as? Column)?.thisArg as? Identifier ?: except as? Identifier ?: continue
            if (atCaret(identifier, pool)) continue
            record(identifier, null, unqualified(pool, identifier))
            columns = columns.filterNot { same(identifier, it.name, columns = true) }
        }
        for (replace in (star.args["replace"] as? List<*>).orEmpty().filterIsInstance<Alias>()) {
            val value = replace.thisArg as? Expression ?: continue
            expression(value, frame, env)
            origins.setTargets[value]?.let { target -> (target.thisArg as? Identifier)?.let { record(it, null, unqualified(pool, it)) } }
            val name = replace.alias
            val replacement = projected(replace, context)
            val index = columns.indexOfFirst { same(name, it.name) }
            if (index >= 0) columns = columns.toMutableList().also { it[index] = replacement }
        }
        for (rename in (star.args["rename"] as? List<*>).orEmpty().filterIsInstance<Alias>()) {
            val old = (rename.thisArg as? Column)?.thisArg as? Identifier ?: continue
            if (atCaret(old, pool)) continue
            record(old, null, unqualified(pool, old))
            val index = columns.indexOfFirst { same(old, it.name, columns = true) }
            val target = rename.args["alias"] as? Identifier
            if (index >= 0) columns = columns.toMutableList().also {
                it[index] = it[index].copy(name = target?.let(::stored) ?: rename.alias, declaration = target?.let(::declared))
            }
        }
        return columns to knowledge
    }

    private fun expandStar(bindings: List<Binding>, merged: List<ScopeColumn>): List<ScopeColumn> {
        val all = bindings.flatMap { it.columns }
        if (merged.isEmpty()) return all
        val rest = all.filterNot { column -> merged.any { same(it.name, column.name) } }
        if (dialect.usingColumnOrder != UsingColumnOrder.IN_PLACE) return merged + rest
        val out = ArrayList<ScopeColumn>()
        val placed = HashSet<String>()
        for (column in all) {
            val key = merged.firstOrNull { same(it.name, column.name) }
            if (key == null) out += column else if (placed.add(fold(key.name))) out += key
        }
        return out
    }

    private fun qualifiedStar(column: Column, frame: Frame): List<Binding> {
        val qualifier = column.qualifier()
        val last = qualifier.lastOrNull() ?: return emptyList()
        val bindings = findBindings(frame, qualifier)
        if (bindings.isEmpty()) {
            val absence = unqualified(frame, qualifier.first()).knowledge
            reference(ReferenceKind.QUALIFIER, last, Resolution.UNRESOLVED, absence, start = qualifier.first())
            if (absence == Knowledge.FULL) diagnose(ScopeProblem.UNKNOWN_QUALIFIER, "Unknown relation '${qualifier.joinToString(".") { it.name }}'", qualifier.first(), last)
        } else {
            qualify(qualifier, bindings)
        }
        return bindings
    }

    /** A qualifier's reference: its relation, or every one it could mean. */
    private fun qualify(qualifier: List<Identifier>, bindings: List<Binding>) {
        val single = bindings.singleOrNull()
        reference(ReferenceKind.QUALIFIER, qualifier.last(), if (single != null) Resolution.RESOLVED else Resolution.AMBIGUOUS,
            bindings.fold(Knowledge.FULL) { knowledge, it -> worst(knowledge, it.knowledge) }, relation = single?.relation, start = qualifier.first())
    }

    /** A projection's output column: the referenced column, renamed, or a computed one. */
    private fun projected(projection: Expression, context: String): ScopeColumn {
        val value = projection.unwrapped()
        val alias = (projection as? Alias)?.args?.get("alias") as? Identifier
        val name = (alias ?: (value as? Column)?.thisArg as? Identifier)?.let(::stored) ?: (projection as? Alias)?.alias ?: value.outputName
        // SET's replacement alias is synthetic; the SET target names the column.
        val declaration = alias?.let(::declared) ?: origins.setTargets[projection.thisArg]?.let { (it.thisArg as? Identifier)?.let(::declared) }
        resolved[value]?.let { return it.copy(name = name, relation = "", relationKind = null, declaration = declaration ?: it.declaration) }
        val by = origins.stages[projection] ?: origins.stages[projection.thisArg] ?: origins.stages[value] ?: context
        return ScopeColumn(name, "", ColumnOrigin.COMPUTED, nullabilityOf(value), sqlTypeOf(value), computedBy = by, declaration = declaration)
    }

    private fun nullabilityOf(value: Expression): Nullability {
        resolved[value]?.let { return it.nullability }
        val window = value as? Window
        if (window != null && window.thisArg.let { it is RowNumber || it is Rank ||
                it is DenseRank || it is Ntile }) return Nullability.NON_NULL
        val verdict = expressionNullability(window?.thisArg as? Expression ?: value, dialect) { column ->
            when (resolved[column]?.nullability) {
                Nullability.NULLABLE -> true
                Nullability.NON_NULL -> false
                else -> null
            }
        }
        return when (verdict) {
            true -> Nullability.NULLABLE
            false -> Nullability.NON_NULL
            null -> Nullability.UNKNOWN
        }
    }

    /** The type from the expression and its columns' exact SQL types, never from display types. */
    private fun sqlTypeOf(value: Expression): String? {
        val copy = value.copy()
        for ((original, typed) in value.walk().zip(copy.walk())) {
            val sqlType = resolved[original]?.sqlType ?: continue
            typed.typeSlot = try {
                dataTypeFromStr(sqlType, dialect)
            } catch (_: ParseError) {
                continue
            }
        }
        val type = annotateTypes(copy, dialect = dialect, overwriteTypes = false).type as? DataType ?: return null
        return if (type.thisArg == DType.UNKNOWN) null else Dialects.BASE.generate(type)
    }

    private fun aggregated(projection: Any?): Boolean =
        projection is Expression && projection.walk().any { it is AggFunc || it is Window }

    // ------------------------------------------------------------------ set operations

    /** A left-deep chain (`a UNION b UNION c ...`) combines branch by branch, without recursing per operator. */
    private fun setOperation(node: SetOperation, env: Env): Row {
        val chain = generateSequence(node) { it.left as? SetOperation }.toList().asReversed()
        var row = query(chain.first().left, env)
        for (operation in chain) {
            checkCanceled()
            val scoped = if (operation === node) env else withClause(operation, env)
            row = combine(operation, row, query(operation.right, scoped))
            orderBy(operation, Frame(listOf(anonymous(row)), env.outer), scoped)
        }
        return row
    }

    private fun combine(node: SetOperation, left: Row, right: Row): Row {
        val operator = origins.operators[node.right] ?: operatorRange(node)
        val label = operator?.second ?: node.key.uppercase()
        return when {
            node.args["by_name"] == true -> byName(node, left, right, label)
            left.knowledge == Knowledge.FULL && right.knowledge == Knowledge.FULL && left.columns.size != right.columns.size -> {
                operator?.first?.let { span ->
                    diagnostics += ScopeDiagnostic(ScopeProblem.COLUMN_COUNT_MISMATCH, ScopeProblem.COLUMN_COUNT_MISMATCH.severity,
                        "$label branches have ${left.columns.size} and ${right.columns.size} columns", span.first, span.last + 1)
                }
                Row(emptyList(), Knowledge.UNKNOWN)
            }
            else -> Row(left.columns.mapIndexed { index, column -> combine(node, column, listOfNotNull(right.columns.getOrNull(index)), label) },
                worst(left.knowledge, right.knowledge))
        }
    }

    private fun byName(node: SetOperation, left: Row, right: Row, label: String): Row {
        val columns = left.columns.map { column ->
            val other = right.columns.firstOrNull { same(it.name, column.name) }
            if (other == null) column.copy(nullability = Nullability.NULLABLE) else combine(node, column, listOf(other), label)
        } + right.columns.filter { column -> left.columns.none { same(it.name, column.name) } }
            .map { it.copy(nullability = Nullability.NULLABLE) }
        return Row(columns, worst(left.knowledge, right.knowledge))
    }

    private fun combine(node: SetOperation, first: ScopeColumn, others: List<ScopeColumn>, label: String): ScopeColumn {
        if (node is Except || node is Intersect) return first
        if (others.isEmpty()) return ScopeColumn(first.name, "", ColumnOrigin.COMPUTED, Nullability.UNKNOWN, computedBy = label)
        val all = listOf(first) + others
        val nullability = when {
            all.any { it.nullability == Nullability.NULLABLE } -> Nullability.NULLABLE
            all.all { it.nullability == Nullability.NON_NULL } -> Nullability.NON_NULL
            else -> Nullability.UNKNOWN
        }
        if (first.source != null && all.all { it.source == first.source }) return first.copy(nullability = nullability)
        return ScopeColumn(first.name, "", ColumnOrigin.COMPUTED, nullability,
            all.map { it.sqlType?.let(::canonical) }.distinct().singleOrNull(), computedBy = label)
    }

    /** The set operator between the branches, `UNION ALL` included, and its text. */
    private fun operatorRange(node: SetOperation): Pair<IntRange, String>? = names.start(node.right)?.let(names::operatorBefore)

    // ------------------------------------------------------------------ pivots

    private fun pivot(binding: Binding, pivot: Pivot, env: Env): Binding {
        val frame = Frame(listOf(binding), env.outer)
        val label = (pivot.args["alias"] as? TableAlias)?.thisArg as? Identifier
        val fields = (pivot.args["fields"] as? List<*>).orEmpty().filterIsInstance<In>()
        val stage = origins.stages[pivot]
        val unpivot = pivot.args["unpivot"] == true
        val used = ArrayList<ScopeColumn>()
        fun use(node: Expression) {
            expression(node, frame, env)
            node.walk().filterIsInstance<Column>().forEach { column -> resolved[column]?.let { used += it } }
        }
        val output = if (unpivot) {
            fields.forEach { field -> field.expressionsArg.filterIsInstance<Expression>().forEach(::use) }
            val values = used
            val includeNulls = pivot.args["include_nulls"] == true
            val valueNullability = when {
                !includeNulls -> Nullability.NON_NULL
                values.any { it.nullability == Nullability.NULLABLE } -> Nullability.NULLABLE
                values.all { it.nullability == Nullability.NON_NULL } -> Nullability.NON_NULL
                else -> Nullability.UNKNOWN
            }
            val by = stage ?: "UNPIVOT"
            val nameColumns = fields.mapNotNull { field ->
                val identifier = field.thisArg as? Identifier
                val name = identifier?.let(::stored) ?: (field.thisArg as? Expression)?.name?.takeIf(String::isNotEmpty)
                name?.let { computed(it, Nullability.NON_NULL, by, "VARCHAR").copy(declaration = identifier?.let(::declared)) }
            }
            val valueColumns = pivot.expressionsArg.filterIsInstance<Expression>().map {
                computed((it as? Identifier)?.let(::stored) ?: it.name, valueNullability, by, values.map { v -> v.sqlType?.let(::canonical) }.distinct().singleOrNull())
                    .copy(declaration = (it as? Identifier)?.let(::declared))
            }
            binding.columns.filterNot { it in values } + nameColumns + valueColumns
        } else {
            pivot.expressionsArg.filterIsInstance<Expression>().forEach(::use)
            fields.forEach { field -> (field.thisArg as? Expression)?.let(::use) }
            val group = (pivot.args["group"] as? Expression)?.let { group ->
                expression(group, frame, env)
                group.walk().filterIsInstance<Column>().mapNotNull { resolved[it] }.toList()
            }
            val aggregates = pivot.expressionsArg.filterIsInstance<Expression>()
            val by = stage ?: "PIVOT"
            val values = fields.flatMap { field -> field.expressionsArg.filterIsInstance<Expression>() }.map { value ->
                val alias = (value as? Alias)?.args?.get("alias") as? Identifier
                (alias?.let(::stored) ?: (value.unwrapped() as? Literal)?.name ?: value.outputName) to alias?.let(::declared)
            }
            val pivoted = values.flatMap { (value, declaration) ->
                aggregates.map { aggregate ->
                    val suffix = (aggregate as? Alias)?.alias?.takeIf { aggregates.size > 1 }
                    val counting = aggregate.unwrapped() is Count
                    computed(if (suffix == null) value else "${value}_$suffix",
                        if (counting) Nullability.NON_NULL else Nullability.NULLABLE, by).copy(declaration = declaration)
                }
            }
            (group ?: binding.columns.filterNot { it in used }) + pivoted
        }
        val name = label?.let(::stored) ?: binding.name
        val knowledge = binding.knowledge
        return Binding(name, label != null || binding.qualifiable && name.isNotEmpty(),
            ScopeRelation(name, binding.relation.kind, knowledge, binding.relation.source, binding.relation.provenance,
                label?.let(::declared) ?: binding.relation.declaration),
            output.map { it.through(name, binding.relation.kind) })
    }

    private fun values(node: Values, env: Env): Row {
        node.expressionsArg.filterIsInstance<Expression>().forEach { expression(it, env.outer, env) }
        // Unnamed VALUES columns get dialect-specific names (col0, _col0, column1): only an alias's names are known.
        val names = (node.args["alias"] as? TableAlias)?.columns.orEmpty().filterIsInstance<Identifier>()
        // ClickHouse's values('a UInt8, b String', row, ...) declares its columns before the rows.
        if (names.isEmpty() && dialect.tableFunctionArgumentsDeclareColumns) {
            val structure = ((node.expressionsArg.singleOrNull() as? Tuple)?.expressionsArg?.firstOrNull() as? Literal)?.takeIf { it.isString }
            structure?.let { TableFunctionStructure.parse(it.name, dialect.name) }?.let { columns ->
                return Row(columns.map { ScopeColumn(it.name, "", ColumnOrigin.DECLARED, Nullability.UNKNOWN, it.sqlType) }, Knowledge.FULL)
            }
        }
        return Row(names.map { computed(stored(it), Nullability.UNKNOWN, "VALUES").copy(declaration = declared(it)) }, if (names.isEmpty()) Knowledge.UNKNOWN else Knowledge.FULL)
    }

    // ------------------------------------------------------------------ names

    /**
     * Resolves every column reference in [node], evaluating nested queries with [frame] as their
     * outer scope. Iterative: a long condition chain must not exhaust the stack.
     */
    private fun expression(node: Expression?, frame: Frame?, env: Env) {
        if (node == null) return
        val pending = ArrayDeque<Pair<Expression, Set<String>>>()
        pending.addLast(node to emptySet())
        while (pending.isNotEmpty()) {
            val (current, hidden) = pending.removeLast()
            when (current) {
                is Dot -> {
                    // Beyond four components the parser retains a Column under Dots. Treat it as
                    // qualification only when the FROM bindings prove the entire qualifier; otherwise
                    // keep the ordinary struct-field analysis of the inner column.
                    val parts = current.scopeIdentifierParts()
                    val qualifier = parts?.dropLast(1).orEmpty()
                    val bindings = frame?.let { findBindings(it, qualifier) }.orEmpty()
                    if (parts != null && bindings.isNotEmpty()) {
                        val identifier = parts.last()
                        if (atCaret(identifier, frame, qualifier)) continue
                        qualify(qualifier, bindings)
                        record(identifier, current, Lookup(bindings.flatMap { binding -> binding.columns.filter { same(identifier, it.name) } },
                            bindings.fold(Knowledge.FULL) { knowledge, it -> worst(knowledge, it.knowledge) }))
                    } else current.iterExpressions(reverse = true).forEach { pending.addLast(it to hidden) }
                }
                is Column -> {
                    val identifier = current.thisArg as? Identifier
                    if (identifier != null && current.args["table"] == null && hidden.any { same(identifier, it) }) continue
                    if (identifier != null) column(current, identifier, frame)
                    else if (current.thisArg is Star) frame?.let { qualifiedStar(current, it) }
                }
                is Lambda -> {
                    val parameters = current.expressionsArg.filterIsInstance<Identifier>().map(::stored)
                    (current.thisArg as? Expression)?.let { pending.addLast(it to hidden + parameters) }
                }
                is Select, is SetOperation, is Subquery -> query(current, env.withOuter(frame))
                else -> current.iterExpressions(reverse = true).forEach { pending.addLast(it to hidden) }
            }
        }
    }

    /** Records the caret's site if [identifier] is the marker. */
    private fun atCaret(identifier: Identifier, frame: Frame?, qualifier: List<Identifier> = emptyList()): Boolean {
        if (caretMarker == null || caretMarker !in identifier.name) return false
        // Lowering may place the caret's node twice (an AGGREGATE grouping also projects); the first is where it was written.
        if (caret == null) caret = CaretSite(frame, qualifier)
        return true
    }

    private fun column(node: Column, identifier: Identifier, frame: Frame?) {
        if (++resolutions % 256 == 0) checkCanceled()
        val qualifier = node.qualifier()
        if (atCaret(identifier, frame, qualifier)) return
        if (qualifier.isEmpty()) {
            record(identifier, node, frame?.let { unqualified(it, identifier) } ?: Lookup(emptyList(), Knowledge.UNKNOWN))
            return
        }
        if (caretMarker != null && qualifier.any { caretMarker in it.name }) return
        val bindings = frame?.let { findBindings(it, qualifier) }.orEmpty()
        if (bindings.isNotEmpty()) {
            qualify(qualifier, bindings)
            val candidates = bindings.flatMap { binding -> binding.columns.filter { same(identifier, it.name, columns = true) } }
            record(identifier, node, Lookup(candidates, bindings.fold(Knowledge.FULL) { knowledge, it -> worst(knowledge, it.knowledge) }))
            return
        }
        val first = qualifier.first()
        val last = qualifier.last()
        val field = frame?.let { unqualified(it, first) } ?: Lookup(emptyList(), Knowledge.UNKNOWN)
        if (field.candidates.isNotEmpty()) {
            // A struct field: the qualifier is a column, the field is beyond what facts describe.
            record(first, null, field)
            record(identifier, null, Lookup(emptyList(), Knowledge.UNKNOWN))
            return
        }
        if (field.knowledge != Knowledge.FULL) {
            // It may be a struct column of a relation whose columns are not all known, where `a.b` reads a
            // field; elsewhere it can only name a relation, and none of the query's has that name.
            val knowledge = if (dialect.dottedFieldAccess) field.knowledge else Knowledge.FULL
            reference(ReferenceKind.QUALIFIER, last, Resolution.UNRESOLVED, knowledge, start = first)
            record(identifier, null, Lookup(emptyList(), Knowledge.UNKNOWN))
            return
        }
        reference(ReferenceKind.QUALIFIER, last, Resolution.UNRESOLVED, Knowledge.FULL, start = first)
        diagnose(ScopeProblem.UNKNOWN_QUALIFIER, "Unknown relation '${qualifier.joinToString(".") { it.name }}'", first, last)
        record(identifier, null, Lookup(emptyList(), Knowledge.FULL), report = false)
    }

    /** A column's qualifier parts, outermost first: `catalog.db.t`. */
    private fun Column.qualifier(): List<Identifier> =
        listOf("catalog", "db", "table").mapNotNull { args[it] as? Identifier }

    class Lookup(val candidates: List<ScopeColumn>, val knowledge: Knowledge) {
        val single: ScopeColumn? get() = candidates.singleOrNull()
    }

    fun unqualified(frame: Frame, identifier: Identifier): Lookup {
        var current: Frame? = frame
        while (current != null) {
            val found = matches(current, identifier)
            if (found.isNotEmpty()) return Lookup(found, current.absence)
            val absence = current.absence
            if (absence != Knowledge.FULL) return Lookup(emptyList(), absence)
            current = current.outer
        }
        return Lookup(emptyList(), Knowledge.FULL)
    }

    private fun matches(frame: Frame, identifier: Identifier): List<ScopeColumn> {
        fun List<ScopeColumn>.matching() = filter { same(identifier, it.name, columns = true) }
        if (frame.aliasesFirst) frame.aliases.matching().takeIf { it.isNotEmpty() }?.let { return it }
        frame.merged.matching().takeIf { it.isNotEmpty() }?.let { return it }
        frame.bindings.flatMap { it.columns.matching() }.takeIf { it.isNotEmpty() }?.let { return it }
        return if (frame.aliasesFirst) emptyList() else frame.aliases.matching()
    }

    /** The relations [qualifier] names in the nearest frame that has one: a suffix of their path, part by part. */
    fun findBindings(frame: Frame, qualifier: List<Identifier>): List<Binding> {
        var current: Frame? = frame
        while (current != null) {
            current.bindings.filter { binding ->
                binding.qualifiable && binding.path.size >= qualifier.size &&
                    binding.path.takeLast(qualifier.size).zip(qualifier).all { (part, written) -> same(written, part, columns = false) }
            }.takeIf { it.isNotEmpty() }?.let { return it }
            current = current.outer
        }
        return emptyList()
    }

    /** Every column an unqualified name could reach in [frame], nearest first. */
    fun visible(frame: Frame?): List<ScopeColumn> {
        val out = ArrayList<ScopeColumn>()
        var current = frame
        while (current != null) {
            out += current.bindings.flatMap { it.columns }
            out += current.merged
            out += current.aliases.filter { alias ->
                alias.name.isNotEmpty() && out.none { it == alias || same(it.name, alias.name) && it.passedOnAs(alias) }
            }
            current = current.outer
        }
        return out
    }

    /** Whether [alias] is this column as a projection passes it on ([projected]), from the same fact or the same column of the SQL. */
    private fun ScopeColumn.passedOnAs(alias: ScopeColumn): Boolean =
        source != null && source == alias.source || copy(name = alias.name, relation = "", relationKind = null) == alias

    private fun record(identifier: Identifier, column: Expression?, lookup: Lookup, report: Boolean = true) {
        val span = names.range(identifier) ?: return
        val resolution = when (lookup.candidates.size) {
            0 -> Resolution.UNRESOLVED
            1 -> Resolution.RESOLVED
            else -> Resolution.AMBIGUOUS
        }
        if (column != null && resolution == Resolution.RESOLVED) resolved[column] = lookup.candidates.single()
        references += ScopeReference(ReferenceKind.COLUMN, identifier.name, span.first, span.last + 1, resolution, lookup.knowledge, lookup.candidates)
        if (!report) return
        when {
            resolution == Resolution.AMBIGUOUS -> diagnose(ScopeProblem.AMBIGUOUS_COLUMN,
                "Ambiguous column '${identifier.name}': ${lookup.candidates.joinToString { it.qualified() }}", identifier, identifier)
            resolution == Resolution.RESOLVED -> {}
            lookup.knowledge == Knowledge.FULL -> diagnose(ScopeProblem.MISSING_COLUMN, "Unknown column '${identifier.name}'", identifier, identifier)
            lookup.knowledge == Knowledge.PARTIAL -> diagnose(ScopeProblem.PARTIAL_CONTRACT,
                "Column '${identifier.name}' is not in the partial relation contract", identifier, identifier)
        }
    }

    /** Where SQL writes [identifier], or null for a name lowering made up. */
    private fun declared(identifier: Identifier): ScopeRange? = names.range(identifier)?.let { ScopeRange(it.first, it.last + 1) }

    private fun reference(
        kind: ReferenceKind,
        identifier: Identifier,
        resolution: Resolution,
        knowledge: Knowledge,
        relation: ScopeRelation? = null,
        start: Identifier? = null,
    ) {
        val span = names.range(identifier) ?: return
        val first = start?.let(names::range)?.first ?: span.first
        references += ScopeReference(kind, identifier.name, first, span.last + 1, resolution, knowledge, relation = relation)
    }

    private fun diagnose(problem: ScopeProblem, message: String, first: Expression, last: Expression) {
        val start = names.range(first)?.first ?: return
        val end = names.range(last)?.last?.plus(1) ?: return
        if (end > start) diagnostics += ScopeDiagnostic(problem, problem.severity, message, start, end)
    }

    // ------------------------------------------------------------------ identifier rules

    private val strategy = dialect.normalizationStrategy

    private fun folds(columns: Boolean) = strategy == NormalizationStrategy.CASE_INSENSITIVE ||
        strategy == NormalizationStrategy.CASE_INSENSITIVE_UPPERCASE || columns && dialect.caseInsensitiveColumns

    private fun fold(text: String, upper: Boolean = false): String = when {
        dialect.asciiOnlyNormalization -> buildString(text.length) {
            for (c in text) append(if (upper && c in 'a'..'z') c - 32 else if (!upper && c in 'A'..'Z') c + 32 else c)
        }
        upper -> text.uppercase()
        else -> text.lowercase()
    }

    /** The name SQL stores for [identifier]: an unquoted name folds where the dialect folds it on input. */
    private fun stored(identifier: Identifier): String = stored(identifier.name, identifier.quoted)

    private fun stored(text: String, quoted: Boolean): String = when {
        quoted -> text
        strategy == NormalizationStrategy.LOWERCASE -> fold(text)
        strategy == NormalizationStrategy.UPPERCASE -> fold(text, upper = true)
        else -> text
    }

    /** Whether [written] names the stored or provider name [name]. */
    private fun same(written: Identifier, name: String, columns: Boolean = true): Boolean = same(stored(written), name, columns)

    /** Whether two stored or provider names are the same; only folding dialects ignore case. */
    private fun same(left: String, right: String, columns: Boolean = true): Boolean =
        left == right || folds(columns) && fold(left) == fold(right)

    private fun catalogPath(key: String): List<String> = catalogPaths.getOrPut(key) { catalogTableParts(key).map { it.name } }

    /** A table's qualifying path: as written, completed by the catalog key's leading parts. */
    private fun path(parts: List<Identifier>, key: String): List<String> {
        val keys = catalogPath(key)
        return keys.take(keys.size - parts.size) + parts.map(::stored)
    }

    /**
     * Whether the table [key], named by its last [named] parts, lies in [namespace], a dotted path as
     * stored: the namespace supplies the leading components the name leaves out (`one.sales` makes
     * `sales.orders` `one.sales.orders` and `orders` `one.sales.orders`), or, shorter than those, their
     * innermost ones (`sales` for `one.sales.orders`).
     */
    private fun inNamespace(key: String, named: Int, namespace: String): Boolean {
        val missing = catalogPath(key).dropLast(named)
        val path = catalogPath(namespace)
        return if (missing.size <= path.size) missing == path.take(missing.size) else missing.takeLast(path.size) == path
    }

    private fun sameTable(key: String, parts: List<Identifier>): Boolean {
        val keys = catalogPath(key)
        if (keys.size < parts.size || parts.isEmpty()) return false
        return parts.reversed().zip(keys.reversed()).all { (part, name) -> same(part, name, columns = false) }
    }

    private fun ColumnFact.visible(relation: String, label: String, kind: RelationKind) =
        ScopeColumn(name, label, origin, nullability, sqlType, displayType, ColumnSource(relation, this, kind), provenance = provenance)
            .through(label, kind)

    /** [this] as seen through the relation [name] of [kind], or through none when [name] is empty. */
    private fun ScopeColumn.through(name: String, kind: RelationKind) =
        copy(relation = name, relationKind = kind.takeIf { name.isNotEmpty() })

    private val canonicalTypes = HashMap<String, String>()

    /** A SQL type in the spelling computed types use, so a fact's and a computed type compare; unparsable text stays as given. */
    private fun canonical(sqlType: String): String = canonicalTypes.getOrPut(sqlType) {
        try {
            Dialects.BASE.generate(dataTypeFromStr(sqlType, dialect))
        } catch (_: ParseError) {
            sqlType
        }
    }

    private fun computed(name: String, nullability: Nullability, by: String, sqlType: String? = null) =
        ScopeColumn(name, "", ColumnOrigin.COMPUTED, nullability, sqlType, computedBy = by)

}

/** What an absent name means where both apply: unknown over partial over full. */
internal fun worst(a: Knowledge, b: Knowledge) = when {
    a == Knowledge.UNKNOWN || b == Knowledge.UNKNOWN -> Knowledge.UNKNOWN
    a == Knowledge.PARTIAL || b == Knowledge.PARTIAL -> Knowledge.PARTIAL
    else -> Knowledge.FULL
}

internal fun ScopeColumn.qualified(): String = if (relation.isEmpty()) name else "$relation.$name"

private tailrec fun Expression.unwrapped(): Expression {
    if (this !is Alias && this !is Paren) return this
    return (thisArg as? Expression ?: return this).unwrapped()
}

/** Node positions in script offsets: parse positions shifted by the region's start. */
internal class SourceNames(private val base: Int, private val tokens: List<Token>) {
    fun positioned(node: Expression): Boolean = SourceMap.sourcePosOf(node) != null

    /** [node]'s own token range, inclusive, in script offsets. */
    fun range(node: Expression): IntRange? = SourceMap.sourcePosOf(node)?.takeIf { it.start >= 0 && it.end >= it.start }
        ?.let { base + it.start..base + it.end }

    fun start(node: Expression): Int? = node.walk().mapNotNull { range(it)?.first }.minOrNull()

    private val operators = tokens.indices.filter { tokens[it].tokenType in SET_OPERATORS }

    /** The first set operator at or after script offset [from], with a following ALL or DISTINCT. */
    fun operatorFrom(from: Int): Pair<IntRange, String>? =
        operators.firstOrNull { base + tokens[it].start >= from }?.let(::operator)

    /** The last set operator before script offset [before]: the one ahead of a set operation's right branch. */
    fun operatorBefore(before: Int): Pair<IntRange, String>? {
        val position = operators.binarySearch { (base + tokens[it].start).compareTo(before) }
        val insertion = if (position >= 0) position else -position - 1
        return operators.getOrNull(insertion - 1)?.let(::operator)
    }

    private fun operator(index: Int): Pair<IntRange, String> {
        val first = tokens[index]
        val next = tokens.getOrNull(index + 1)?.takeIf { it.tokenType == TokenType.ALL ||
            it.tokenType == TokenType.DISTINCT }
        val last = next ?: first
        return (base + first.start..base + last.end) to listOfNotNull(first, next).joinToString(" ") { it.text.uppercase() }
    }

    private companion object {
        val SET_OPERATORS = setOf(
            TokenType.UNION,
            TokenType.INTERSECT,
            TokenType.EXCEPT,
        )
    }
}
