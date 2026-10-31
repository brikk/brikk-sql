package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.Set

private val clickhouseLambdaSyntaxNameKeys = setOf("alias", "table", "db", "catalog", "unit", "kind", "name", "field")

/**
 * CHSEM-003 argument binding and CHSEM-005's separately proved value results.
 * Visit its array inputs before entering the lambda, with independent lexical scopes
 * for explicit parameters and implicit placeholders. The ordinary annotator owns
 * physical columns and scalar fallbacks; exact results require separate native proofs.
 */
internal class ClickhouseLambdaTyping(
    private val alreadyAnnotated: (Expression) -> Boolean,
    private val annotateNode: (Expression) -> Unit,
    private val bindType: (Expression, DataType?) -> Unit,
    private val usesGenericArithmetic: (Expression) -> Boolean,
    private val inferredTypes: Map<Long, DataType> = emptyMap(),
    private val invalidValues: Set<Long> = emptySet(),
) {
    private class Bindings(
        val parent: Bindings?,
        val names: Map<String, DataType?> = emptyMap(),
        val implicit: List<DataType?>? = null,
    ) {
        fun namedOwner(name: String): Bindings? {
            var current: Bindings? = this
            while (current != null) {
                if (name in current.names) return current
                current = current.parent
            }
            return null
        }

        fun implicitTypes(): List<DataType?>? {
            var current: Bindings? = this
            while (current != null) {
                current.implicit?.let { return it }
                current = current.parent
            }
            return null
        }
    }

    private data class Frame(
        val node: Expression,
        val finish: Boolean = false,
        val bindings: Bindings? = null,
        val arrayInputs: List<Expression>? = null,
    )

    fun annotate(root: Expression) {
        val stack = ArrayDeque<Frame>()
        val refined = HashSet<Long>()
        stack.addLast(Frame(root))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val node = frame.node
            if (alreadyAnnotated(node)) continue
            if (frame.finish) {
                val bound = bindReference(node, frame.bindings)
                val dependsOnRefinement = (bound && !node.isType(DType.UNKNOWN)) ||
                    node.iterExpressions().any { it.objectId in refined }
                if (!bound) {
                    if (frame.bindings != null && dependsOnRefinement && numericOperation(node) && usesGenericArithmetic(node)) {
                        // Generic SQLGlot arithmetic does not prove ClickHouse widths.
                        // Do not turn a newly known UInt8 argument into a guessed result.
                        bindType(node, null)
                    } else annotateNode(node)
                }
                val inferred = inferredTypes[node.objectId]
                if (node.objectId in invalidValues) bindType(node, null)
                else if (inferred != null) bindType(node, inferred)
                if (dependsOnRefinement || inferred != null) refined.add(node.objectId)
                continue
            }

            // Native lambdas cannot capture through a SQL subquery. Identifiers in
            // column/table/type declarations name syntax, not lambda argument values.
            var bindings = if (node is Query || node is Subquery || node is DataType || node is ColumnDef ||
                node is Column || node is Table || node is TableAlias || node is ClickhouseFunctionReference)
                null else frame.bindings
            if (node is Lambda || node is ClickhouseImplicitLambda) {
                val parameters = node.expressionsArg.filterIsInstance<Identifier>()
                val inputs = frame.arrayInputs
                val types = if (inputs != null && inputs.size == parameters.size && parameters.size == node.expressionsArg.size &&
                    parameters.map { it.name }.distinct().size == parameters.size &&
                    (node !is ClickhouseImplicitLambda || (parameters.size <= 128 && unambiguousPlaceholders(node)))) {
                    inputs.map(::arrayElementType)
                } else List(parameters.size) { null }
                bindings = if (node is Lambda) {
                    Bindings(bindings, parameters.mapIndexed { i, p -> p.name to types[i] }.toMap())
                } else Bindings(bindings, implicit = types)
            }
            stack.addLast(Frame(node, finish = true, bindings = bindings))

            val call = arrayMapArguments(node)
            if (call != null) {
                val (lambda, inputs) = call
                stack.addLast(Frame(lambda, bindings = bindings, arrayInputs = inputs))
                for (child in node.iterExpressions()) {
                    if (child !== lambda) stack.addLast(Frame(child, bindings = bindings))
                }
            } else if (node is ClickhouseComposition) {
                // Do not reuse initial array elements at a later stage. Its argument
                // annotations come only from CHSEM-005's proved result overrides.
                (node.args["expression"] as? Expression)?.let { stack.addLast(Frame(it, bindings = bindings)) }
                (node.thisArg as? Expression)?.let { stack.addLast(Frame(it, bindings = bindings, arrayInputs = frame.arrayInputs)) }
            } else {
                for (child in node.iterExpressions()) {
                    val inputs = if (node is Paren && child === node.thisArg) frame.arrayInputs else null
                    stack.addLast(Frame(child, bindings = bindings, arrayInputs = inputs))
                }
            }
        }
    }

    private fun arrayMapArguments(node: Expression): Pair<Expression, List<Expression>>? =
        clickhouseHigherOrderCall(node)?.let { call -> call.argument?.let { it to call.inputs } }

    private fun arrayElementType(expression: Expression): DataType? {
        val array = expression.type as? DataType ?: return null
        // ClickHouse arrays themselves cannot be nullable. Generic literal-array
        // inference has unspecified nullability and generic numeric widths, not a
        // native element-type proof. Use declared/schema/otherwise known arrays.
        if (array.thisArg != DType.ARRAY || array.args["nullable"] != false || array.expressionsArg.size != 1) return null
        return array.expressionsArg.single() as? DataType
    }

    private fun numericOperation(node: Expression): Boolean = node is Add || node is Sub || node is Mul ||
        node is Div || node is IntDiv || node is Mod || node is Neg || node is BitwiseAnd ||
        node is BitwiseOr || node is BitwiseXor || node is BitwiseNot ||
        node is BitwiseLeftShift || node is BitwiseRightShift

    private fun bindReference(node: Expression, bindings: Bindings?): Boolean {
        if (bindings == null) return false
        if (node is ClickhouseLambdaArgument) {
            val ordinal = node.args["ordinal"] as? Int ?: return false
            bindType(node, bindings.implicitTypes()?.getOrNull(ordinal - 1))
            return true
        }
        if (node !is Identifier) return false
        val parent = node.parent
        if (parent is ClickhouseImplicitLambda && node.argKey == "expressions") {
            bindType(node, bindings.implicitTypes()?.getOrNull(node.index ?: -1))
            return true
        }
        // These identifiers name syntax, physical columns or fields, not parameter values.
        if (parent is Column || parent is Table || parent is TableAlias || parent is DataType ||
            parent is ColumnDef || parent is ClickhouseLambdaArgument || parent is ClickhouseFunctionReference ||
            (parent is Dot && node.argKey != "this") || (parent is Anonymous && node.argKey == "this") ||
            node.argKey in clickhouseLambdaSyntaxNameKeys) return false
        val owner = bindings.namedOwner(node.name) ?: return false
        bindType(node, owner.names[node.name])
        return true
    }

    private fun unambiguousPlaceholders(lambda: ClickhouseImplicitLambda): Boolean {
        var anonymous = false
        var numbered = false
        val body = lambda.thisArg as? Expression ?: return false
        for (node in body.walk(prune = { it !== body &&
            (it is ClickhouseImplicitLambda || it is ClickhouseComposition || it is Query || it is Subquery) })) {
            if (node !is ClickhouseLambdaArgument) continue
            val ordinal = node.args["ordinal"] as? Int ?: return false
            if (ordinal !in 1..128) return false
            if (node.name == "_") anonymous = true else numbered = true
        }
        return !anonymous || !numbered
    }
}
