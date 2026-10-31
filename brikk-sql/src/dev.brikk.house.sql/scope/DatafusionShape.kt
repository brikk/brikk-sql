package dev.brikk.house.sql.scope

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.shape.*

/** Share argument-aware rows with Engine shapes without turning table calls into executable subqueries. */
internal fun datafusionShape(tree: Expression, inputs: ShapeCatalog, dialect: Dialect): Shape {
    if (!tree.isQuery()) throw ShapeError("Cannot derive an output shape from a '${tree.key}' node.")
    fun fact(shape: Shape, origin: ColumnOrigin) = RelationFact(shape.columns.filter { it.name != "*" }.map {
        ColumnFact(it.name, it.type.takeUnless { it == "UNKNOWN" }, nullability = when (it.nullable) {
            true -> Nullability.NULLABLE; false -> Nullability.NON_NULL; null -> Nullability.UNKNOWN
        }, origin = origin)
    }, if (shape.columns.any { it.name == "*" }) Knowledge.PARTIAL else Knowledge.FULL)
    // SqlFragment's preparation rewrites bound slots to tables under its synthetic namespace.
    val slotDepth = inputs.tables.keys.maxOfOrNull { catalogTableParts(it).size } ?: 1
    val slotTables = inputs.slots.mapKeys { (name, _) -> (List(slotDepth - 1) { "__slots" } + name).joinToString(".") }
        .mapValues { fact(it.value, ColumnOrigin.DECLARED) }
    val facts = RelationFacts(tables = inputs.tables.mapValues { fact(it.value, ColumnOrigin.PHYSICAL_TABLE) } + slotTables,
        slots = inputs.slots.mapValues { fact(it.value, ColumnOrigin.DECLARED) })
    val evaluator = ScopeEvaluator(dialect, facts, SourceNames(0, emptyList()), PipeOrigins(), {})
    val row = evaluator.query(tree, Env(emptyList(), null))
    return Shape(row.columns.map { ColumnShape(it.name, it.sqlType ?: "UNKNOWN", when (it.nullability) {
        Nullability.NULLABLE -> true; Nullability.NON_NULL -> false; Nullability.UNKNOWN -> null
    }, dialect.caseSensitive(it.name)) } + if (row.knowledge != Knowledge.FULL) listOf(ColumnShape("*", "UNKNOWN")) else emptyList())
}
