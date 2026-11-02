package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialect
import kotlin.String

/** Argument roles and conservative inventories for the optimizer. ScopeEvaluator supplies editor references. */
internal object SpiceCalls {
    private val search = setOf("vector_search", "text_search", "rrf", "rerank")
    fun name(call: Anonymous): String? = call.name.lowercase().takeIf {
        (call.thisArg !is Identifier || call.name == it) && it in search + setOf("flatten_json", "flatten_json_properties", "json_tree")
    }

    fun dataset(value: Expression?): Table? {
        if (value is Table) return value
        val parts = (value as? Column)?.parts?.filterIsInstance<Identifier>() ?: return null
        if (parts.size !in 1..3) return null
        return Table(args("this" to parts.last().copy(), "db" to parts.getOrNull(parts.size - 2)?.copy(), "catalog" to parts.getOrNull(parts.size - 3)?.copy()))
    }

    fun type(call: Anonymous, column: String, depth: Int = 0, lookup: (Table, String) -> DataType?): DataType? {
        if (depth > 64) return null
        val name = name(call) ?: return null
        val positional = call.expressionsArg.filterIsInstance<Expression>().filterNot { it is Kwarg }
        if (name in setOf("flatten_json", "flatten_json_properties", "json_tree")) {
            val columns = columns(call) { emptyList() }.orEmpty()
            return if (column !in columns) null else DatafusionTypes.logical(if (name == "json_tree" && column in setOf("id", "parent")) DType.BIGINT else DType.VARCHAR)
        }
        if (column == "_score" && name in setOf("vector_search", "text_search") && column in columns(call) { emptyList() }.orEmpty()) return DatafusionTypes.logical(DType.DOUBLE)
        if (name == "rrf" && column == "_fused_score") return DatafusionTypes.logical(DType.DOUBLE)
        if (name == "rerank" && column == "rerank_score") return DatafusionTypes.logical(DType.FLOAT)
        if (name == "rrf" && (column in setOf("rank", "_score") || column.endsWith("_embedding")) || name == "rerank" && column in setOf("_score", "_fused_score")) return null
        fun input(value: Expression?): DataType? = when (value) {
            is Anonymous -> type(value, column, depth + 1, lookup)
            else -> dataset(value)?.let { lookup(it, column) }
        }
        val types = (if (name == "rrf") positional else positional.take(1)).map(::input)
        return types.firstOrNull()?.takeIf { first -> types.all { it != null && it == first } }
    }

    fun columns(call: Anonymous, depth: Int = 0, lookup: (Table) -> List<String>): List<String>? {
        if (depth > 64) return listOf("*")
        val name = name(call) ?: return null
        val positional = call.expressionsArg.filterIsInstance<Expression>().filterNot { it is Kwarg }
        fun input(value: Expression?): List<String> = when (value) {
            is Anonymous -> columns(value, depth + 1, lookup) ?: listOf("*")
            else -> dataset(value)?.let(lookup)?.takeIf { it.isNotEmpty() } ?: listOf("*")
        }
        return when (name) {
            "flatten_json", "flatten_json_properties" -> listOf("path", "parent_path", "key", "value", "type")
            "json_tree" -> listOf("key", "value", "type", "atom", "id", "parent", "fullkey", "path")
            "vector_search", "text_search" -> {
                val option = call.expressionsArg.filterIsInstance<Kwarg>().firstOrNull { (it.thisArg as? Expression)?.name == "include_score" }?.expressionArg
                    ?: positional.drop(2).firstOrNull { it is dev.brikk.house.sql.ast.Boolean }
                val score = if (option == null || (option as? dev.brikk.house.sql.ast.Boolean)?.thisArg == true) listOf("_score") else emptyList()
                input(positional.firstOrNull()) + score + "*"
            }
            "rrf" -> {
                val inputs = positional.map(::input)
                listOf("_fused_score") + inputs.firstOrNull().orEmpty().filter { c ->
                    c !in setOf("*", "rank", "_score") && !c.endsWith("_embedding") && inputs.all { c in it }
                } + "*"
            }
            else -> input(positional.firstOrNull()).filterNot { it in setOf("_score", "_fused_score") } + "rerank_score"
        }
    }

    fun lineage(call: Anonymous, column: String, scope: Scope, schema: Schema?, dialect: Dialect, depth: Int = 0): Node? {
        if (depth > 64) return null
        val name = name(call) ?: return null
        val positional = call.expressionsArg.filterIsInstance<Expression>().filterNot { it is Kwarg }
        val derived = column == when (name) {
            "rrf" -> "_fused_score"; "rerank" -> "rerank_score"; else -> "_score"
        }
        val document = call.expressionsArg.filterIsInstance<Kwarg>().firstOrNull { (it.thisArg as? Expression)?.name == "document" }
            ?.expressionArg.let { it as? Literal }?.takeIf { it.isString }?.name
        val node = Node("$name.$column", call, call)
        if (name !in search) return node
        val inputs = if (name == "rrf") positional else positional.take(1)
        for (input in inputs) {
            if (input is Anonymous) {
                val inputColumn = if (derived && name == "rerank") document ?: "*" else if (derived) when (input.name.lowercase()) {
                    "rrf" -> "_fused_score"; "rerank" -> "rerank_score"; else -> "_score"
                } else column
                lineage(input, inputColumn, scope, schema, dialect, depth + 1)?.let { node.downstream.add(it) }
                continue
            }
            val table = dataset(input) ?: continue
            val cte = if (table.db.isEmpty() && table.catalog.isEmpty()) scope.cteSources[table.name] as? Scope else null
            if (cte != null) {
                val names = cte.expression.namedSelects
                val wanted = if (derived && name == "rerank" && document != null) listOf(document) else if (derived) names.filter { it != "*" } else listOf(column)
                for (field in wanted.filter { it in names }) {
                    node.downstream.add(toNode(field, cte, dialect, schema = schema))
                }
            } else if (derived || schema?.columnNames(table)?.contains(column) == true) {
                node.downstream.add(Node("${table.parts.joinToString(".") { it.name }}.${if (derived && name == "rerank") document ?: "*" else if (derived) "*" else column}", table, table))
            }
        }
        if (!derived && node.downstream.isEmpty()) node.downstream.add(Node(column, Placeholder(), Placeholder()))
        return node
    }
}
