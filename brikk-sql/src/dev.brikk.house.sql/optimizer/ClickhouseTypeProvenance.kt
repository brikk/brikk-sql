package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.diagnostics.clickhouseScriptSemanticStatements
import dev.brikk.house.sql.dialects.sql

/** Evidence for a type slot, never a parser/acceptance/execution verdict. */
enum class ClickhouseTypeEvidence {
    /** A bounded native rule proved this conditional type on the exact engine pin. */
    NATIVE_CONDITIONAL,
    /** A populated annotation without a separately recorded native proof, including generic propagation. */
    GENERIC_FALLBACK,
    /** No usable type was established. Function-valued nodes also stay unknown. */
    UNKNOWN,
}

/** Immutable type spelling captured at analysis time, not a reference to mutable AST type data. */
data class ClickhouseNodeTypeFact(
    val nodeId: Long,
    val typeSql: String?,
    val evidence: ClickhouseTypeEvidence,
)

/**
 * Snapshot keyed by [Expression.objectId] for one exact AST analysis. Every walked node has a fact.
 * Generic propagation is conservatively unproved even when its spelling happens to equal native.
 * These facts are conditional on supplied schema/settings and successful dispatch/execution.
 * They neither suppress semantic errors nor claim that an UNKNOWN node is invalid.
 *
 * Facts are external to the AST: copy/serde do not transfer them, and they retain no AST references.
 * Mutation of SQL, bindings, settings or type slots invalidates this snapshot; re-run analysis.
 * Node IDs are process-local identities, not persistent keys. Do not persist/rebind this report
 * by tree position or SQL equality, or use a report from another call or another script unit.
 */
class ClickhouseTypeProvenance internal constructor(
    val engineVersion: String,
    facts: List<ClickhouseNodeTypeFact>,
) {
    val facts: List<ClickhouseNodeTypeFact> = facts.toList()
    private val byId = facts.associateBy { it.nodeId }
    fun factFor(node: Expression): ClickhouseNodeTypeFact? = byId[node.objectId]
}

/** The same mutated AST as the statement API, plus its external analysis-time type facts. */
data class ClickhouseSemanticTypeAnnotation(
    val expression: Expression,
    val provenance: ClickhouseTypeProvenance,
)

/** Owned script AST/report with no provenance when framing or parsing prevented annotation. */
data class ClickhouseScriptTypeAnnotationWithProvenance(
    val annotation: ClickhouseScriptTypeAnnotation,
    val provenance: ClickhouseTypeProvenance?,
)

/** Opt-in type evidence. Existing AST-only APIs and generic annotateTypes are unchanged. */
fun annotateClickhouseSemanticTypesWithProvenance(
    expression: Expression,
    engineVersion: String,
    schema: Schema? = null,
    checkCanceled: () -> Unit = {},
): ClickhouseSemanticTypeAnnotation = annotateClickhouseTypesAndProvenance(
    expression, engineVersion, schema, emptyMap(), checkCanceled)

/** Explicit sequential script mode, using the same framing, state and diagnostics as the existing API. */
fun annotateClickhouseScriptSemanticTypesWithProvenance(
    script: String,
    engineVersion: String,
    schema: Schema? = null,
    checkCanceled: () -> Unit = {},
): List<ClickhouseScriptTypeAnnotationWithProvenance> {
    requireClickhouseProvenanceInputs(engineVersion, schema)
    checkCanceled()
    return clickhouseScriptSemanticStatements(script, engineVersion, schema, checkCanceled) { expression, settings, report ->
        val typed = expression?.let {
            annotateClickhouseTypesAndProvenance(it, engineVersion, schema, settings, checkCanceled)
        }
        ClickhouseScriptTypeAnnotationWithProvenance(ClickhouseScriptTypeAnnotation(typed?.expression, report), typed?.provenance)
    }
}

private fun requireClickhouseProvenanceInputs(engineVersion: String, schema: Schema?) {
    require(engineVersion == "26.10.2.103") { "Native semantic typing requires the researched ClickHouse pin 26.10.2.103" }
    require(schema == null || schema.dialect?.name == "clickhouse") { "Expected a ClickHouse schema" }
}

private fun annotateClickhouseTypesAndProvenance(
    expression: Expression, engineVersion: String, schema: Schema?, settings: Map<String, Boolean?>,
    checkCanceled: () -> Unit,
): ClickhouseSemanticTypeAnnotation {
    requireClickhouseProvenanceInputs(engineVersion, schema)
    checkCanceled()
    val annotator = clickhouseSemanticAnnotator(engineVersion, schema, settings, checkCanceled)
    annotator.annotate(expression)
    val proved = annotator.clickhouseProvedTypes()
    val facts = expression.walk().map { node ->
        checkCanceled()
        val type = node.type as? DataType
        val evidence = when {
            type == null || type.isType(DType.UNKNOWN) -> ClickhouseTypeEvidence.UNKNOWN
            proved[node.objectId] == type -> ClickhouseTypeEvidence.NATIVE_CONDITIONAL
            else -> ClickhouseTypeEvidence.GENERIC_FALLBACK
        }
        ClickhouseNodeTypeFact(node.objectId, type?.sql("clickhouse"), evidence)
    }.toList()
    return ClickhouseSemanticTypeAnnotation(expression, ClickhouseTypeProvenance(engineVersion, facts))
}
