package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.metadata.CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.Set
import kotlin.reflect.KClass

/** Intrinsic argument-rule failures, not a native whole-query rejection verdict. */
internal enum class ClickhouseCompositionRule(val nativeErrorCode: Int) {
    VARIADIC_LEFT_DESIGNATOR(36), INVALID_OPERAND(36), MIXED_PLACEHOLDERS(36),
    PLACEHOLDER_LIMIT(36), ANONYMOUS_MEMBER(36), ANONYMOUS_DEPTH(36),
    RIGHT_OPERAND_ARITY(42), CALL_ARGUMENT_COUNT(42), ARRAY_MAP_ARITY(43), ARRAY_INPUT_TYPE(43), INPUT_TYPE(43),
    HIGHER_ORDER_ARGUMENT_COUNT(42), HIGHER_ORDER_ARITY(43), PREDICATE_TYPE(43), MAP_INPUT_TYPE(43), MAP_RESULT_TYPE(36),
}

internal enum class ClickhouseCompositionUnknownReason {
    ENGINE_VERSION, NAME_RESOLUTION, UNRESEARCHED_CALLABLE, INPUT_TYPE, RESULT_FAMILY,
    NULL_OR_WRAPPER, CONSTANT_ARGUMENT, TIMEZONE_VALUE, RUNTIME_RESTRICTION,
    ARRAY_LENGTHS, UNSUPPORTED_CONTEXT, PARSER_GAP, ANALYSIS_LIMIT,
}

internal data class ClickhouseCompositionError(
    val expression: Expression,
    val rule: ClickhouseCompositionRule,
    val message: String,
)

internal data class ClickhouseCompositionUnknown(
    val expression: Expression,
    val reason: ClickhouseCompositionUnknownReason,
)

/**
 * CHSEM-004's internal rule report, deliberately without an accepted/isValid flag.
 * Empty errors are not acceptance: resolution, values, execution, array lengths and
 * unsupported forms can remain unchecked. CHSEM-006's diagnostics adapter exposes
 * these errors and unknowns separately with script positions.
 */
internal data class ClickhouseCompositionValidation(
    val errors: List<ClickhouseCompositionError>,
    val unknowns: List<ClickhouseCompositionUnknown>,
)

/** Exact conditional types, not a native acceptance or successful-execution verdict. */
internal data class ClickhouseCompositionInference(
    val valueTypes: Map<Long, DataType>,
    /** Function values retain UNKNOWN in the AST; this records their result separately. */
    val callableResults: Map<Long, DataType>,
    val validation: ClickhouseCompositionValidation,
    /** Intrinsic errors prevent a generic container rule from filling a rejected call. */
    val invalidValues: Set<Long>,
)

internal fun inferClickhouseCompositionResults(
    expression: Expression,
    engineVersion: String,
    schema: Schema? = null,
    knownTypes: Map<Long, DataType> = emptyMap(),
    ignoredClasses: Set<KClass<out Expression>> = emptySet(),
    ignoredIds: Set<Long> = emptySet(),
    checkCanceled: () -> Unit = {},
): ClickhouseCompositionInference = ClickhouseCompositionValidator(engineVersion, schema, checkCanceled,
    inferResults = true, knownTypes = knownTypes, ignoredClasses = ignoredClasses, ignoredIds = ignoredIds).infer(expression)

/**
 * Read-only checks of structured arrayMap callable arguments on the exact 26.10 pin.
 * Native casts and explicit [schema] facts establish input families and a closed
 * physical-name namespace. Generic annotator results/widths are not native proofs.
 * Does not parse, mutate, execute, annotate result types or replace the native verifier.
 */
internal fun validateClickhouseCompositions(
    expression: Expression,
    engineVersion: String,
    schema: Schema? = null,
    checkCanceled: () -> Unit = {},
): ClickhouseCompositionValidation = ClickhouseCompositionValidator(engineVersion, schema, checkCanceled).validate(expression)

private class ClickhouseCompositionValidator(
    private val version: String,
    private val schema: Schema?,
    private val checkCanceled: () -> Unit,
    private val inferResults: Boolean = false,
    private val knownTypes: Map<Long, DataType> = emptyMap(),
    private val ignoredClasses: Set<KClass<out Expression>> = emptySet(),
    private val ignoredIds: Set<Long> = emptySet(),
) {
    private enum class Family { STRING, FIXED_STRING, NUMBER, DATE, DATETIME, OTHER }
    private data class Value(
        val family: Family? = null,
        val plain: Boolean = false,
        val constant: Boolean? = null,
        val element: Value? = null,
        val type: DataType? = null,
        val parts: List<Value>? = null,
    )
    private data class Callable(val arity: Int? = null, val result: Value = Value())
    private enum class Position { MAP, LEFT, RIGHT }
    private class Context(
        val parent: Context? = null,
        val lexical: Set<String> = emptySet(),
        val columns: Set<String> = emptySet(),
        val uncertainColumns: Boolean = false,
        val values: Map<String, Value> = emptyMap(),
        val implicit: List<Value>? = null,
        val physicalValues: Map<Pair<String, String>, Value> = emptyMap(),
        val sqlBoundary: Boolean = false,
        val callableScope: Boolean = false,
    ) {
        fun blocked(name: String): Boolean {
            var scope: Context? = this
            while (scope != null) {
                if (name in scope.lexical || scope.uncertainColumns || name in scope.columns) return true
                scope = scope.parent
            }
            return false
        }
        fun namedValue(name: String): Value? {
            var scope: Context? = this
            while (scope != null) {
                if (name in scope.values) return scope.values[name]
                if (scope.sqlBoundary) return null
                scope = scope.parent
            }
            return null
        }
        fun implicitValue(ordinal: Int): Value {
            var scope: Context? = this
            while (scope != null) {
                scope.implicit?.let { return it.getOrNull(ordinal - 1) ?: Value() }
                if (scope.sqlBoundary) return Value()
                scope = scope.parent
            }
            return Value()
        }
        fun columnValue(column: Column): Value {
            var scope: Context? = this
            while (scope != null) {
                val candidates = scope.physicalValues.filterKeys { (table, name) -> name == column.name &&
                    (column.table.isEmpty() || table == column.table) }.values
                if (column.table.isEmpty() && column.name in scope.values) {
                    // prefer_column_name_to_alias and incomplete catalogs can change
                    // which expression this means. Do not choose an alias by default.
                    if (scope.uncertainColumns || candidates.isNotEmpty()) return Value()
                    return scope.values[column.name] ?: Value()
                }
                if (candidates.size == 1) return candidates.single()
                if (candidates.size > 1 || scope.sqlBoundary) return Value()
                scope = scope.parent
            }
            return Value()
        }
    }
    private data class Visit(val node: Expression, val context: Context)
    private data class Call(val name: String, val arguments: List<Expression>)
    private val errors = LinkedHashMap<Pair<Long, ClickhouseCompositionRule>, ClickhouseCompositionError>()
    private val unknowns = LinkedHashMap<Pair<Long, ClickhouseCompositionUnknownReason>, ClickhouseCompositionUnknown>()
    private val lambdaContexts = HashMap<Long, Context>()
    private val handledCompositions = HashSet<Long>()
    private val valueTypes = LinkedHashMap<Long, DataType>()
    private val callableResults = LinkedHashMap<Long, DataType>()
    private val mapResults = HashMap<Long, Value>()
    private val invalidMaps = HashSet<Long>()
    private var quietFindings = 0

    fun infer(root: Expression): ClickhouseCompositionInference {
        val validation = validate(root)
        return ClickhouseCompositionInference(valueTypes.toMap(), callableResults.toMap(), validation, invalidMaps.toSet())
    }

    fun validate(root: Expression): ClickhouseCompositionValidation {
        if (version != "26.10.2.103") {
            unknown(root, ClickhouseCompositionUnknownReason.ENGINE_VERSION)
            return report()
        }
        val visits = ArrayDeque<Visit>()
        visits.addLast(Visit(root, Context()))
        while (visits.isNotEmpty()) {
            checkCanceled()
            val (node, inherited) = visits.removeLast()
            if (node is Query && node !is Select) {
                unknown(node, ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT)
                continue // Set-operation WITH/alias resolution needs its own scope proof.
            }
            if (node is With) {
                if (node.walk().any { it is ClickhouseComposition || it is ClickhouseImplicitLambda || it is Lambda || arrayMap(it) != null })
                    unknown(node, ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT)
                continue // Definitions may be unused; do not validate them as executed expressions.
            }
            if ((node is Lambda || node is ClickhouseImplicitLambda) && node.objectId !in lambdaContexts) {
                unknown(node, ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT)
                continue // An unresearched enclosing function may not resolve/evaluate this body.
            }
            val context = when (node) {
                is Select -> queryContext(node, inherited)
                is Lambda, is ClickhouseImplicitLambda -> lambdaContexts[node.objectId] ?: bind(node, null, inherited)
                else -> inherited
            }
            if (inferResults) {
                rememberBinding(node, context)
                if (context.callableScope) rememberAtomicCall(node, context)
            }
            val map = arrayMap(node)
            if (map != null) {
                val name = map.name
                if (context.blocked(name)) {
                    unknown(node, ClickhouseCompositionUnknownReason.NAME_RESOLUTION)
                    continue
                }
                if (CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG.getAll(name).none { it.name == map.kind.function }) {
                    unknown(node, ClickhouseCompositionUnknownReason.UNRESEARCHED_CALLABLE)
                    continue
                }
                mapValue(node, map, context, 0)
            } else if (node is ClickhouseComposition && node.objectId !in handledCompositions) {
                unknown(node, ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT)
                continue
            }
            for (child in node.iterExpressions(reverse = true)) visits.addLast(Visit(child, context))
        }
        return report()
    }

    private fun report() = ClickhouseCompositionValidation(errors.values.toList(), unknowns.values.toList())
    private fun unknown(node: Expression, reason: ClickhouseCompositionUnknownReason) {
        if (quietFindings > 0) return
        unknowns.putIfAbsent(node.objectId to reason, ClickhouseCompositionUnknown(node, reason))
    }
    private fun error(node: Expression, rule: ClickhouseCompositionRule, message: String) {
        if (quietFindings > 0) return
        errors.putIfAbsent(node.objectId to rule, ClickhouseCompositionError(node, rule, message))
    }

    private fun ignored(node: Expression): Boolean = inferResults &&
        (node.objectId in ignoredIds || node::class in ignoredClasses)

    private fun remember(node: Expression, result: Value): Value {
        if (inferResults && !ignored(node)) result.type?.let { valueTypes[node.objectId] = it.copy() as DataType }
        return result
    }

    private fun rememberCallable(node: Expression, result: Callable): Callable {
        if (inferResults && !ignored(node)) result.result.type?.let { callableResults[node.objectId] = it.copy() as DataType }
        return result
    }

    /** Parameters stay typed even inside a scalar function whose result is unresearched. */
    private fun rememberBinding(node: Expression, context: Context) {
        if (node is ClickhouseLambdaArgument) {
            remember(node, context.implicitValue(node.args["ordinal"] as? Int ?: 0))
            return
        }
        if (node !is Identifier) return
        val parent = node.parent
        if (parent is ClickhouseImplicitLambda && node.argKey == "expressions") {
            remember(node, context.implicitValue((node.index ?: -1) + 1)); return
        }
        if (parent is Column || parent is Table || parent is TableAlias || parent is ColumnDef ||
            parent is ClickhouseLambdaArgument || parent is ClickhouseFunctionReference ||
            (parent is Dot && node.argKey != "this") || (parent is Anonymous && node.argKey == "this") ||
            node.argKey in setOf("alias", "table", "db", "catalog", "unit", "kind", "name", "field")) return
        var ancestor = parent
        while (ancestor != null && ancestor !is Lambda && ancestor !is ClickhouseImplicitLambda && ancestor !is Query) {
            if (ancestor is DataType || ancestor is ColumnDef) return
            ancestor = ancestor.parent
        }
        context.namedValue(node.name)?.let { remember(node, it) }
    }

    /** Conditional subexpression types do not eagerly validate an opaque parent's arguments. */
    private fun rememberAtomicCall(node: Expression, context: Context) {
        if (ignored(node) || node.objectId in valueTypes) return
        val call = scalarCall(node) ?: return
        if (context.blocked(call.name)) return
        val facts = clickhouseCallableFacts(call.name, version) ?: return
        fun argument(raw: Expression): Value = when (val expression = raw.unnest()) {
            is Identifier -> context.namedValue(expression.name) ?: Value()
            is ClickhouseLambdaArgument -> context.implicitValue(expression.args["ordinal"] as? Int ?: 0)
            is Column -> context.columnValue(expression)
            else -> declaredValue(expression)
        }
        quietFindings++
        val result = try { checkCall(node, facts, call.arguments.map(::argument)) } finally { quietFindings-- }
        remember(node, result)
    }

    private fun mapValue(node: Expression, map: ClickhouseHigherOrderCall, context: Context, depth: Int): Value {
        if (map.kind != ClickhouseHigherOrderKind.MAP) return higherOrderValue(node, map, context, depth)
        if (ignored(node)) { unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY); return Value() }
        if (inferResults) mapResults[node.objectId]?.let { return it }
        if (depth > 128) { unknown(node, ClickhouseCompositionUnknownReason.ANALYSIS_LIMIT); return Value() }
        val previousErrors = errors.size
        val (argument, arrays) = map
        if (argument == null || arrays.isEmpty()) {
            unknown(node, ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT); return Value()
        }
        val inputs = arrays.map { arrayElement(it, context, depth + 1) }
        val callable = callable(argument, inputs, Position.MAP, context, depth + 1)
        if (callable.arity != null && callable.arity != arrays.size)
            error(argument, ClickhouseCompositionRule.ARRAY_MAP_ARITY,
                "arrayMap supplies ${arrays.size} array arguments, but its callable requires ${callable.arity}.")
        if (arrays.size > 1) unknown(node, ClickhouseCompositionUnknownReason.ARRAY_LENGTHS)
        if (inferResults && errors.size > previousErrors) invalidMaps.add(node.objectId)
        val result = if (inferResults && node.objectId !in invalidMaps && callable.arity == arrays.size && arrays.isNotEmpty() && callable.result.type != null) {
            val array = arrayType(callable.result.type)
            if (completeType(array)) Value(Family.OTHER, true, element = callable.result, type = array)
            else { unknown(node, ClickhouseCompositionUnknownReason.ANALYSIS_LIMIT); Value() }
        } else Value()
        if (inferResults && result.type == null && node.objectId !in invalidMaps)
            unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY)
        if (inferResults && result.type != null) mapResults[node.objectId] = result
        return remember(node, result)
    }

    private fun declaredArity(raw: Expression?, depth: Int = 0): Int? {
        if (depth > 128) return null
        val node = raw?.unnest() ?: return null
        return when (node) {
            is Lambda, is ClickhouseImplicitLambda -> node.expressionsArg.size
            is ClickhouseComposition -> declaredArity(node.thisArg as? Expression, depth + 1)
            is ClickhouseFunctionReference -> (clickhouseCallableFacts(node.name, version)?.designatorArity as? ClickhouseDesignatorArity.Fixed)?.count
            else -> null
        }
    }

    private fun higherOrderValue(node: Expression, call: ClickhouseHigherOrderCall, context: Context, depth: Int): Value {
        if (ignored(node)) { unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY); return Value() }
        if (depth > 128) { unknown(node, ClickhouseCompositionUnknownReason.ANALYSIS_LIMIT); return Value() }
        if (inferResults) mapResults[node.objectId]?.let { return it }
        if (call.inputs.isEmpty() || (call.argument == null && !call.kind.optionalLambda)) {
            error(node, ClickhouseCompositionRule.HIGHER_ORDER_ARGUMENT_COUNT, "${call.name} requires a callable and data inputs.")
            return Value()
        }
        val previousErrors = errors.size
        val sources = call.inputs.map { inputValue(it, context, depth + 1) }
        val arity = declaredArity(call.argument)
        val inputs: List<Value>
        if (call.kind == ClickhouseHigherOrderKind.APPLY) {
            if (sources.size != 1) { unknown(node, ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT); return Value() }
            val source = sources.single()
            if (source.type?.thisArg != DType.MAP || source.parts?.size != 2) {
                if (source.plain && source.type?.thisArg != DType.MAP)
                    error(call.inputs.single(), ClickhouseCompositionRule.MAP_INPUT_TYPE, "mapApply requires a Map input.")
                else unknown(node, ClickhouseCompositionUnknownReason.INPUT_TYPE)
                return Value()
            }
            inputs = if (arity == 1) listOf(tupleValue(source.parts)) else source.parts
        } else {
            inputs = call.inputs.map { arrayElement(it, context, depth + 1, call.name) }
            // Single-array tuple unpacking is a separate CHSEM-009 proof, not an arity error.
            if (inputs.size == 1 && arity != null && arity > 1 &&
                (inputs.single().type?.thisArg == DType.STRUCT || inputs.single().family == null)) {
                unknown(node, ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT); return Value()
            }
        }
        val callable = call.argument?.let { callable(it, inputs, Position.MAP, context, depth + 1) }
            ?: Callable(inputs.size, inputs.firstOrNull() ?: Value())
        if (callable.arity != null && callable.arity != inputs.size)
            error(call.argument ?: node, ClickhouseCompositionRule.HIGHER_ORDER_ARITY,
                "${call.name} supplies ${inputs.size} arguments, but its callable requires ${callable.arity}.")
        if (sources.size > 1) unknown(node, ClickhouseCompositionUnknownReason.ARRAY_LENGTHS)
        val body = callable.result
        var supported = callable.arity == inputs.size
        if (call.kind.predicate) {
            if (body.plain && body.type?.thisArg == DType.UTINYINT) Unit
            else {
                if (body.plain && (body.type != null || body.family in setOf(Family.STRING, Family.FIXED_STRING)))
                    error(call.argument ?: node, ClickhouseCompositionRule.PREDICATE_TYPE,
                        "${call.name} requires a UInt8 predicate result; nullable/wrapper results need separate evidence.")
                else unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY)
                supported = false
            }
        }
        var result = when (call.kind) {
            ClickhouseHigherOrderKind.FILTER, ClickhouseHigherOrderKind.SORT -> {
                if (call.kind == ClickhouseHigherOrderKind.SORT && (!body.plain || body.type == null ||
                        body.family !in setOf(Family.NUMBER, Family.STRING, Family.FIXED_STRING))) supported = false
                sources.first()
            }
            ClickhouseHigherOrderKind.EXISTS, ClickhouseHigherOrderKind.ALL -> nativeType(plainType(DType.UTINYINT))
            ClickhouseHigherOrderKind.APPLY -> {
                val parts = body.parts
                if (parts?.size == 2) {
                    val key = parts[0]
                    val value = parts[1]
                    if (!key.plain || key.family !in setOf(Family.STRING, Family.FIXED_STRING, Family.NUMBER) || key.type == null || value.type == null) {
                        supported = false; Value()
                    } else nativeType(DataType(args("this" to DType.MAP, "nullable" to false, "nested" to true,
                        "expressions" to listOf(key.type.copy(), value.type.copy()))))
                } else {
                    if (body.plain || parts != null) error(call.argument ?: node, ClickhouseCompositionRule.MAP_RESULT_TYPE,
                        "mapApply requires a two-element Tuple result containing the new key and value.")
                    supported = false; Value()
                }
            }
            else -> Value()
        }
        if (errors.size > previousErrors) invalidMaps.add(node.objectId)
        if (!supported || node.objectId in invalidMaps || result.type == null || !completeType(result.type)) {
            if (node.objectId !in invalidMaps) unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY)
            result = Value()
        }
        if (inferResults && result.type != null) mapResults[node.objectId] = result
        return remember(node, result)
    }

    private fun tupleValue(parts: List<Value>): Value {
        val type = if (parts.all { it.type != null }) DataType(args("this" to DType.STRUCT, "nullable" to false,
            "nested" to true, "expressions" to parts.map { it.type!!.copy() })) else null
        return Value(Family.OTHER, parts.all { it.plain }, type = type, parts = parts)
    }

    private fun comparisonValue(node: Expression, context: Context, depth: Int): Value {
        val operands = listOfNotNull(node.thisArg as? Expression, node.args["expression"] as? Expression)
            .map { value(it, context, depth + 1) }
        if (operands.size == 2 && operands.all { it.plain } &&
            (operands.all { it.family == Family.NUMBER } || operands.all { it.family in setOf(Family.STRING, Family.FIXED_STRING) }))
            return nativeType(plainType(DType.UTINYINT))
        unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY)
        return Value()
    }

    private fun callable(raw: Expression, inputs: List<Value>?, position: Position, context: Context, depth: Int): Callable {
        checkCanceled()
        val node = raw.unnest()
        if (depth > 128) { unknown(node, ClickhouseCompositionUnknownReason.ANALYSIS_LIMIT); return Callable() }
        if (ignored(node)) { unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY); return Callable() }
        if (node is ClickhouseComposition) {
            handledCompositions.add(node.objectId)
            val left = node.thisArg as? Expression
            val right = node.args["expression"] as? Expression
            if (left == null || right == null) { unknown(node, ClickhouseCompositionUnknownReason.PARSER_GAP); return Callable() }
            val first = callable(left, inputs, Position.LEFT, context, depth + 1)
            val second = callable(right, listOf(first.result), Position.RIGHT, context, depth + 1)
            if (second.arity != null && second.arity != 1)
                error(right, ClickhouseCompositionRule.RIGHT_OPERAND_ARITY, "A right composition operand must take one argument, not ${second.arity}.")
            return rememberCallable(node, Callable(first.arity,
                if (inferResults && second.arity != 1) Value() else second.result))
        }
        if (node is ClickhouseFunctionReference) {
            val facts = facts(node.name, node, context) ?: return Callable()
            val arity = when (val declared = facts.designatorArity) {
                is ClickhouseDesignatorArity.Fixed -> declared.count
                ClickhouseDesignatorArity.Variadic -> when (position) {
                    Position.LEFT -> {
                        error(node, ClickhouseCompositionRule.VARIADIC_LEFT_DESIGNATOR,
                            "${node.name} has variadic native arity; use an explicit placeholder call on the left of composition.")
                        return Callable()
                    }
                    Position.RIGHT -> 1
                    Position.MAP -> { unknown(node, ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT); return Callable() }
                }
            }
            val result = if (inputs != null && inputs.size == arity) checkCall(node, facts, inputs) else Value()
            return rememberCallable(node, Callable(arity, result))
        }
        if (node is Lambda || node is ClickhouseImplicitLambda) {
            val arity = if (node is Lambda) {
                val parameters = node.expressionsArg.filterIsInstance<Identifier>()
                if (parameters.size != node.expressionsArg.size || parameters.map { it.name }.distinct().size != parameters.size) {
                    unknown(node, ClickhouseCompositionUnknownReason.PARSER_GAP); return Callable()
                }
                parameters.size
            } else {
                val references = placeholders(node)
                // Outside composition, native lifting yields to scoped names. A bare
                // identity placeholder also yields to scoped names inside composition.
                val body = (node.thisArg as? Expression)?.unnest()
                val localNames = Context(context, lexical = bodyAliases(node))
                if ((position == Position.MAP || body is ClickhouseLambdaArgument) &&
                    references.any { localNames.blocked(it.name) }) {
                    unknown(node, ClickhouseCompositionUnknownReason.NAME_RESOLUTION); return Callable()
                }
                placeholderArity(node, references) ?: return Callable()
            }
            val bound = bind(node, inputs?.takeIf { it.size == arity }, context)
            lambdaContexts[node.objectId] = bound
            val body = node.thisArg as? Expression
            return rememberCallable(node, Callable(arity, body?.let { value(it, bound, depth + 1) } ?: Value()))
        }
        // CHSEM-011: parenthesized explicit lambda operands currently become JSON arrows.
        if (node is JSONExtract || node is Dot || node is Column || node is Identifier) {
            unknown(node, ClickhouseCompositionUnknownReason.PARSER_GAP)
            return Callable()
        }
        error(node, ClickhouseCompositionRule.INVALID_OPERAND,
            "A composition operand must be a function, explicit lambda or expression with placeholders.")
        return Callable()
    }

    private fun placeholders(lambda: Expression): List<ClickhouseLambdaArgument> {
        val body = lambda.thisArg as? Expression ?: return emptyList()
        return body.walk(bfs = false, prune = { it !== body &&
            (it is ClickhouseImplicitLambda || it is ClickhouseComposition || it is Query || it is Subquery) })
            .onEach { checkCanceled() }
            .filterIsInstance<ClickhouseLambdaArgument>().toList()
    }

    private fun placeholderArity(lambda: Expression, references: List<ClickhouseLambdaArgument>): Int? {
        if (references.isEmpty()) { unknown(lambda, ClickhouseCompositionUnknownReason.PARSER_GAP); return null }
        if (references.any { (it.args["ordinal"] as? Int) !in 1..128 }) {
            error(lambda, ClickhouseCompositionRule.PLACEHOLDER_LIMIT, "Numbered placeholders must be between _1 and _128.")
            return null
        }
        val anonymous = references.filter { it.name == "_" }
        if (anonymous.isNotEmpty() && anonymous.size != references.size) {
            error(lambda, ClickhouseCompositionRule.MIXED_PLACEHOLDERS, "Anonymous and numbered placeholders cannot be mixed in one operand.")
            return null
        }
        if (anonymous.isNotEmpty()) {
            val body = (lambda.thisArg as? Expression)?.unnest()
            for (reference in anonymous) {
                var parent = reference.parent
                while (parent is Paren) parent = parent.parent
                if (parent is Dot) {
                    error(reference, ClickhouseCompositionRule.ANONYMOUS_MEMBER, "Anonymous placeholder member access requires a numbered placeholder.")
                    return null
                }
                if (reference !== body && parent !== body) {
                    error(reference, ClickhouseCompositionRule.ANONYMOUS_DEPTH, "An anonymous placeholder must be a direct call argument; use numbered placeholders at deeper levels.")
                    return null
                }
            }
        }
        return references.maxOf { it.args["ordinal"] as Int }
    }

    private fun bind(lambda: Expression, inputs: List<Value>?, context: Context): Context {
        val names = lambda.expressionsArg.filterIsInstance<Identifier>()
        val aliases = bodyAliases(lambda)
        val values = inputs?.takeIf { it.size == names.size } ?: List(names.size) { Value() }
        if (inferResults) for ((index, name) in names.withIndex()) {
            if (name.name !in aliases) remember(name, values[index])
        }
        return if (lambda is Lambda) Context(context, lexical = names.map { it.name }.toSet() + aliases,
            values = names.mapIndexed { i, name -> name.name to if (name.name in aliases) Value() else values[i] }.toMap(), callableScope = true)
        else Context(context, lexical = aliases, implicit = values, callableScope = true)
    }

    private fun bodyAliases(lambda: Expression): Set<String> {
        val body = lambda.thisArg as? Expression ?: return emptySet()
        return body.walk(prune = { it is Lambda || it is ClickhouseImplicitLambda || it is Query || it is Subquery })
            .onEach { checkCanceled() }.filterIsInstance<Alias>().map { it.alias }.toSet()
    }

    private fun value(raw: Expression, context: Context, depth: Int): Value {
        checkCanceled()
        val node = raw.unnest()
        if (depth > 128) { unknown(node, ClickhouseCompositionUnknownReason.ANALYSIS_LIMIT); return Value() }
        if (ignored(node)) { unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY); return Value() }
        val nestedMap = if (inferResults) arrayMap(node) else null
        if (nestedMap != null) {
            val name = nestedMap.name
            if (context.blocked(name)) { unknown(node, ClickhouseCompositionUnknownReason.NAME_RESOLUTION); return Value() }
            if (CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG.getAll(name).none { it.name == nestedMap.kind.function }) {
                unknown(node, ClickhouseCompositionUnknownReason.UNRESEARCHED_CALLABLE); return Value()
            }
            return mapValue(node, nestedMap, context, depth + 1)
        }
        return remember(node, when (node) {
            is ClickhouseLambdaArgument -> context.implicitValue(node.args["ordinal"] as? Int ?: 0)
            is Identifier -> context.namedValue(node.name) ?: Value()
            is Column -> context.columnValue(node)
            is Literal -> Value(if (node.isString) Family.STRING else Family.NUMBER, true, true,
                type = if (inferResults && node.isString) plainType(DType.VARCHAR) else null)
            is Null -> Value(constant = true)
            is Tuple -> tupleValue(node.expressionsArg.filterIsInstance<Expression>().map { value(it, context, depth + 1) })
            is EQ, is NEQ, is GT, is GTE, is LT, is LTE -> comparisonValue(node, context, depth)
            is TryCast -> { unknown(node, ClickhouseCompositionUnknownReason.NULL_OR_WRAPPER); Value() }
            is Cast -> {
                val source = node.thisArg as? Expression
                val input = source?.let { value(it, context, depth + 1) } ?: Value()
                if (source == null || !castInputProvesTarget(input, source, node.args["to"] as? DataType)) {
                    unknown(node, ClickhouseCompositionUnknownReason.NULL_OR_WRAPPER); Value()
                } else nativeType(node.args["to"] as? DataType).copy(constant = input.constant)
            }
            is Neg -> if (node.thisArg is Literal && !(node.thisArg as Literal).isString) Value(Family.NUMBER, true, true)
                else { unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY); Value() }
            is Transform -> { unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY); Value() }
            is Query, is Subquery, is Lambda, is ClickhouseImplicitLambda, is ClickhouseComposition -> {
                unknown(node, ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT); Value()
            }
            else -> {
                val call = scalarCall(node)
                if (call == null) { unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY); Value() }
                else if (call.name == "arrayMap") { unknown(node, ClickhouseCompositionUnknownReason.RESULT_FAMILY); Value() }
                else {
                    val facts = facts(call.name, node, context)
                    if (facts == null) Value() else checkCall(node, facts, call.arguments.map { value(it, context, depth + 1) })
                }
            }
        })
    }

    private fun checkCall(node: Expression, facts: ClickhouseCallableFacts, arguments: List<Value>): Value {
        val fixed = facts.designatorArity as? ClickhouseDesignatorArity.Fixed
        if (fixed != null && arguments.size != fixed.count) {
            error(node, ClickhouseCompositionRule.CALL_ARGUMENT_COUNT, "${facts.canonicalName} requires ${fixed.count} arguments, not ${arguments.size}.")
            return Value()
        }
        if (arguments.any { !it.plain }) {
            unknown(node, ClickhouseCompositionUnknownReason.NULL_OR_WRAPPER)
            return Value() // Includes early-NULL branches that bypass variadic argument validation.
        }
        val arity = facts.explicitCallArity
        val maximum = if (arity.secondArgumentRequiresDateTime && arguments.firstOrNull()?.family != Family.DATETIME) 1 else arity.maximum
        if (arguments.size < arity.minimum || (maximum != null && arguments.size > maximum)) {
            error(node, ClickhouseCompositionRule.CALL_ARGUMENT_COUNT, "${facts.canonicalName} does not accept ${arguments.size} arguments for these input types.")
            return Value()
        }
        var supported = true
        fun requireString(index: Int, fixedAllowed: Boolean) {
            val actual = arguments.getOrNull(index) ?: return
            if (actual.family != Family.STRING && !(fixedAllowed && actual.family == Family.FIXED_STRING)) {
                error(node, ClickhouseCompositionRule.INPUT_TYPE, "Argument ${index + 1} of ${facts.canonicalName} requires ${if (fixedAllowed) "String or FixedString" else "String"}.")
                supported = false
            }
        }
        when (facts.inputRule) {
            ClickhouseCallableInputRule.STRING_OR_FIXED_STRING -> requireString(0, true)
            ClickhouseCallableInputRule.TRIM_WITH_OPTIONAL_CONSTANT_STRING -> {
                requireString(0, true)
                if (arguments.size == 2) {
                    requireString(1, false)
                    if (arguments[1].constant != true) { unknown(node, ClickhouseCompositionUnknownReason.CONSTANT_ARGUMENT); supported = false }
                }
            }
            ClickhouseCallableInputRule.PRIMITIVE_NUMERIC_PAIR -> {
                if (arguments.any { it.family != Family.NUMBER }) { unknown(node, ClickhouseCompositionUnknownReason.INPUT_TYPE); supported = false }
            }
            ClickhouseCallableInputRule.SCALAR_TO_STRING -> {
                if (arguments.firstOrNull()?.family !in setOf(Family.NUMBER, Family.STRING, Family.FIXED_STRING, Family.DATE, Family.DATETIME)) {
                    unknown(node, ClickhouseCompositionUnknownReason.INPUT_TYPE); supported = false
                }
                if (arguments.size == 2) { requireString(1, false); unknown(node, ClickhouseCompositionUnknownReason.TIMEZONE_VALUE) }
            }
            ClickhouseCallableInputRule.SCALAR_CONCAT -> {
                if (arguments.any { it.family !in setOf(Family.NUMBER, Family.STRING, Family.FIXED_STRING) }) {
                    unknown(node, ClickhouseCompositionUnknownReason.INPUT_TYPE); supported = false
                }
            }
        }
        if (!supported) return Value()
        if (facts.executionRestriction == ClickhouseCallableExecutionRestriction.FIXED_STRING_UNSUPPORTED &&
            arguments.firstOrNull()?.family == Family.FIXED_STRING) unknown(node, ClickhouseCompositionUnknownReason.RUNTIME_RESTRICTION)
        // Validation mode retains family-only propagation. Inference mode additionally
        // records exact types only within the same supported inputs and count rules.
        return when (facts.resultRule) {
            ClickhouseCallableResultRule.PRESERVE_STRING_INPUT -> arguments.first().copy(constant = null)
            ClickhouseCallableResultRule.STRING -> Value(Family.STRING, true,
                type = if (inferResults) plainType(DType.VARCHAR) else null)
            ClickhouseCallableResultRule.PRIMITIVE_MULTIPLICATION_PROMOTION -> Value(Family.NUMBER, true,
                type = if (inferResults) multiplyType(arguments) else null)
        }
    }

    private fun facts(name: String, node: Expression, context: Context): ClickhouseCallableFacts? {
        if (context.blocked(name)) { unknown(node, ClickhouseCompositionUnknownReason.NAME_RESOLUTION); return null }
        val facts = clickhouseCallableFacts(name, version)
        if (facts == null) unknown(node, ClickhouseCompositionUnknownReason.UNRESEARCHED_CALLABLE)
        return facts
    }

    private fun scalarCall(node: Expression): Call? {
        val original = node.metaOrNull?.get("clickhouse_name") as? String
        return when (node) {
            is Anonymous -> Call(node.name, node.expressionsArg.filterIsInstance<Expression>())
            is Lower -> Call(original ?: "lower", listOfNotNull(node.thisArg as? Expression))
            is Upper -> Call(original ?: "upper", listOfNotNull(node.thisArg as? Expression))
            is Mul -> if (inferResults) Call(original ?: "multiply", listOfNotNull(node.thisArg as? Expression,
                node.args["expression"] as? Expression)) else null
            is Concat -> if (node::class == Concat::class) Call(original ?: "concat", node.expressionsArg.filterIsInstance<Expression>()) else null
            is Trim -> if (original?.equals("trimBoth", ignoreCase = true) == true)
                Call(original, listOfNotNull(node.thisArg as? Expression, node.args["expression"] as? Expression,
                    node.args["position"] as? Expression, node.args["collation"] as? Expression))
                else if (node.args["position"] != null || node.args["collation"] != null) null
                else Call(original ?: "trim", listOfNotNull(node.thisArg as? Expression, node.args["expression"] as? Expression))
            else -> null
        }
    }

    private fun arrayMap(node: Expression): ClickhouseHigherOrderCall? = clickhouseHigherOrderCall(node)

    private fun inputValue(raw: Expression, context: Context, depth: Int): Value = when (val node = raw.unnest()) {
        is Identifier -> context.namedValue(node.name) ?: Value()
        is Column -> context.columnValue(node)
        is ClickhouseLambdaArgument -> context.implicitValue(node.args["ordinal"] as? Int ?: 0)
        else -> if (arrayMap(node) != null) value(node, context, depth + 1) else declaredValue(node)
    }

    private fun arrayElement(raw: Expression, context: Context, depth: Int = 0, function: String = "arrayMap"): Value {
        val node = raw.unnest()
        val input = when (node) {
            is Identifier -> context.namedValue(node.name) ?: Value()
            is Column -> context.columnValue(node)
            is ClickhouseLambdaArgument -> context.implicitValue(node.args["ordinal"] as? Int ?: 0)
            else -> if (inferResults && arrayMap(node) != null) value(node, context, depth + 1) else declaredValue(node)
        }
        if (inferResults) remember(node, input)
        if (input.plain && input.element != null) return input.element.copy(constant = null)
        if (input.plain && input.element == null)
            error(node, ClickhouseCompositionRule.ARRAY_INPUT_TYPE, "$function requires an Array input, not a known scalar or other non-array type.")
        unknown(node, ClickhouseCompositionUnknownReason.INPUT_TYPE)
        return Value()
    }

    /** Only syntax/type declarations, never an inferred generic expression type. */
    private fun declaredValue(raw: Expression, depth: Int = 0): Value {
        checkCanceled()
        val node = raw.unnest()
        if (depth > 128) { unknown(node, ClickhouseCompositionUnknownReason.ANALYSIS_LIMIT); return Value() }
        if (ignored(node)) return Value()
        if (inferResults) knownTypes[node.objectId]?.let { return nativeType(it) }
        return when (node) {
            is TryCast -> Value()
            is Cast -> {
                val source = node.thisArg as? Expression
                if (source != null && castInputProvesTarget(declaredValue(source, depth + 1), source, node.args["to"] as? DataType))
                    nativeType(node.args["to"] as? DataType) else Value()
            }
            is Literal -> Value(if (node.isString) Family.STRING else Family.NUMBER, true,
                type = if (inferResults && node.isString) plainType(DType.VARCHAR) else null)
            is Neg -> if (node.thisArg is Literal && !(node.thisArg as Literal).isString) Value(Family.NUMBER, true) else Value()
            is dev.brikk.house.sql.ast.Array -> {
                val elements = node.expressionsArg.filterIsInstance<Expression>().map { declaredValue(it, depth + 1) }
                val sameFamily = elements.isNotEmpty() && elements.all { it.plain } &&
                    elements.map(::familyOnly).distinct().size == 1
                val element = if (sameFamily) elements.first().copy(type =
                    elements.first().type?.takeIf { candidate -> elements.all { it.type == candidate } }) else Value()
                Value(Family.OTHER, true, element = element,
                    type = if (inferResults && element.type != null) arrayType(element.type) else null)
            }
            else -> Value()
        }
    }

    /** A target spelling alone cannot prove that cast_keep_nullable did not retain wrappers. */
    private fun nonNullCastInput(initial: Value, raw: Expression): Boolean {
        var input = initial
        var expression: Expression? = raw.unnest()
        while (input.plain) {
            if (expression is dev.brikk.house.sql.ast.Array && expression.expressionsArg.isEmpty()) return true
            val element = input.element ?: return input.family != Family.OTHER
            input = element
            expression = (expression as? dev.brikk.house.sql.ast.Array)?.expressionsArg?.firstOrNull()
                ?.let { (it as? Expression)?.unnest() }
        }
        return false
    }

    private fun castInputProvesTarget(input: Value, source: Expression, target: DataType?): Boolean {
        if (nonNullCastInput(input, source)) return true
        if (target?.thisArg == DType.MAP && source.unnest() is VarMap) {
            val map = source.unnest()
            val values = listOf("keys", "values").map { map.args[it] as? dev.brikk.house.sql.ast.Array }
            if (values.all { it != null } && values.all { array -> array!!.expressionsArg.all {
                    it is Expression && declaredValue(it).let { value -> value.plain && value.family != Family.OTHER }
                } }) return true
        }
        if (!inferResults || target == null || !completeType(target)) return false
        // Explicit nullable targets already account for a source NULL wrapper. This
        // copies the declared type; it does not infer a function's null propagation.
        if (target.args["nullable"] == true && (source.unnest() is Null || input.type != null || input.plain)) return true
        val element = target.expressionsArg.singleOrNull() as? DataType
        return target.thisArg == DType.ARRAY && target.args["nullable"] == false &&
            element?.args?.get("nullable") == true && input.plain && input.element != null
    }

    private fun nativeType(type: DataType?, depth: Int = 0): Value {
        checkCanceled()
        if (depth > 128) {
            if (type != null) unknown(type, ClickhouseCompositionUnknownReason.ANALYSIS_LIMIT)
            return Value()
        }
        val exact = if (type != null && completeType(type)) type.copy() as DataType else null
        if (type == null || type.args["nullable"] != false || type.thisArg in setOf(DType.UNKNOWN, DType.NULL,
                DType.LOWCARDINALITY, DType.DYNAMIC, DType.VARIANT)) return Value(type = exact)
        val family = when (type.thisArg) {
            DType.TEXT, DType.VARCHAR -> Family.STRING
            DType.FIXEDSTRING -> Family.FIXED_STRING
            DType.TINYINT, DType.SMALLINT, DType.INT, DType.BIGINT, DType.UTINYINT, DType.USMALLINT,
            DType.UINT, DType.UBIGINT, DType.FLOAT, DType.DOUBLE -> Family.NUMBER
            DType.DATE, DType.DATE32 -> Family.DATE
            DType.DATETIME, DType.DATETIME64 -> Family.DATETIME
            DType.ARRAY, DType.STRUCT, DType.MAP, DType.BOOLEAN, DType.TIME -> Family.OTHER
            // Unmapped SQL type spellings can be native aliases, not incompatible
            // types. For example CHAR is String and TIMESTAMP is DateTime.
            else -> return Value(type = exact)
        }
        return Value(family, true, element = if (type.thisArg == DType.ARRAY)
            nativeType(type.expressionsArg.singleOrNull() as? DataType, depth + 1) else null, type = exact,
            parts = if (type.thisArg in setOf(DType.STRUCT, DType.MAP)) type.expressionsArg.map {
                nativeType(it as? DataType ?: (it as? ColumnDef)?.args?.get("kind") as? DataType, depth + 1)
            } else null)
    }

    private fun familyOnly(value: Value): Value = value.copy(type = null, element = value.element?.let(::familyOnly), parts = value.parts?.map(::familyOnly))

    private fun completeType(root: DataType): Boolean {
        val supported = setOf(DType.TEXT, DType.VARCHAR, DType.FIXEDSTRING, DType.TINYINT, DType.SMALLINT,
            DType.INT, DType.BIGINT, DType.UTINYINT, DType.USMALLINT, DType.UINT, DType.UBIGINT,
            DType.FLOAT, DType.DOUBLE, DType.DATE, DType.DATE32, DType.DATETIME, DType.DATETIME64,
            DType.ARRAY, DType.STRUCT, DType.MAP, DType.BOOLEAN, DType.DECIMAL, DType.DECIMAL32,
            DType.DECIMAL64, DType.DECIMAL128, DType.DECIMAL256)
        val stack = ArrayDeque<Pair<Expression, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            checkCanceled()
            val (node, depth) = stack.removeLast()
            if (depth > 128 || (node is DataType && (node.thisArg !in supported || node.args["nullable"] !is Boolean))) return false
            if (node is DataTypeParam && node.thisArg !is Literal) return false
            for (child in node.iterExpressions()) stack.addLast(child to depth + 1)
        }
        return true
    }

    private fun plainType(dtype: DType) = DataType(args("this" to dtype, "nullable" to false, "nested" to false))
    private fun arrayType(element: DataType) = DataType(args("this" to DType.ARRAY, "nullable" to false,
        "nested" to true, "expressions" to listOf(element.copy())))

    /** The source-pinned NumberTraits rule, only for the ten primitive input kinds. */
    private fun multiplyType(arguments: List<Value>): DataType? {
        val sizes = mapOf(DType.TINYINT to 1, DType.UTINYINT to 1, DType.SMALLINT to 2, DType.USMALLINT to 2,
            DType.INT to 4, DType.UINT to 4, DType.BIGINT to 8, DType.UBIGINT to 8, DType.FLOAT to 4, DType.DOUBLE to 8)
        val types = arguments.map { it.type?.thisArg as? DType ?: return null }
        if (types.size != 2 || types.any { it !in sizes }) return null
        if (types.any { it == DType.FLOAT || it == DType.DOUBLE }) return plainType(DType.DOUBLE)
        val signed = types.any { it in setOf(DType.TINYINT, DType.SMALLINT, DType.INT, DType.BIGINT) }
        val width = (types.maxOf { sizes.getValue(it) } * 2).coerceAtMost(8)
        return plainType(when (width) {
            2 -> if (signed) DType.SMALLINT else DType.USMALLINT
            4 -> if (signed) DType.INT else DType.UINT
            else -> if (signed) DType.BIGINT else DType.UBIGINT
        })
    }

    private fun queryContext(select: Select, inherited: Context): Context {
        val names = mutableSetOf<String>()
        for (node in select.walk(prune = { it !== select && (it is Query || it is Subquery || it is Lambda || it is ClickhouseImplicitLambda) })) {
            checkCanceled()
            if (node is Alias) names.add(node.alias)
        }
        val withNames = (select.args["with_"] as? With)?.expressionsArg.orEmpty().filterIsInstance<CTE>().map { it.aliasOrName }
        names.addAll(withNames)
        val columns = mutableSetOf<String>()
        val physicalValues = mutableMapOf<Pair<String, String>, Value>()
        val expressionValues = mutableMapOf<String, Value>()
        for (projection in select.expressionsArg.filterIsInstance<Alias>())
            (projection.thisArg as? Expression)?.let { expressionValues[projection.alias] = if (ignored(projection)) Value() else declaredValue(it) }
        for (cte in (select.args["with_"] as? With)?.expressionsArg.orEmpty().filterIsInstance<CTE>())
            (cte.thisArg as? Expression)?.let { expressionValues[cte.aliasOrName] = declaredValue(it) }
        var uncertain = false
        val relations = listOfNotNull((select.args["from_"] as? From)?.thisArg as? Expression) +
            (select.args["joins"] as? List<*>)?.filterIsInstance<Join>().orEmpty().mapNotNull { it.thisArg as? Expression }
        for (relation in relations) {
            if (relation is Table && relation.thisArg is Identifier && relation.name !in withNames) {
                val nativeSchema = (schema as? MappingSchema)?.takeIf { it.dialect.name == "clickhouse" }
                val found = nativeSchema?.find(relation, raiseOnMissing = false) as? Map<*, *>
                if (found == null) uncertain = true else {
                    columns.addAll(found.keys.map { it.toString() })
                    for (column in found.keys.map { it.toString() })
                        (relation.aliasOrName to column).let { key ->
                            physicalValues[key] = if (key in physicalValues) Value() else nativeType(nativeSchema.getColumnType(relation, column))
                        }
                }
            } else if (relation is Subquery && relation.thisArg is Select) {
                val projections = (relation.thisArg as Select).expressionsArg.filterIsInstance<Expression>()
                if (projections.isNotEmpty() && projections.all { it is Alias || (it is Column && it.name != "*") })
                    columns.addAll(projections.map { it.aliasOrName }) else uncertain = true
                for (projection in projections.filterIsInstance<Alias>()) {
                    val key = relation.aliasOrName to projection.alias
                    physicalValues[key] = if (key in physicalValues) Value()
                        else if (ignored(projection)) Value()
                        else (projection.thisArg as? Expression)?.let { declaredValue(it) } ?: Value()
                }
            } else uncertain = true
        }
        return Context(inherited, lexical = names, columns = columns, uncertainColumns = uncertain,
            values = expressionValues, physicalValues = physicalValues, sqlBoundary = true)
    }
}
