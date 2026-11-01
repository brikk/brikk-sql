package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.diagnostics.ClickhouseSemanticDiagnostics
import dev.brikk.house.sql.diagnostics.clickhouseScriptSemanticStatements

/**
 * One explicit-script unit. [expression] is a fresh owned AST, or null when framing/parsing
 * prevented annotation. [diagnostics] keeps parser errors, proved semantic errors and unknowns
 * separate. Its start/end are UTF-16 script offsets; AST source ranges are statement-relative
 * and can be translated by adding diagnostics.start. Types remain conditional on dispatch
 * and execution, including when a runtime semantic error is reported.
 */
data class ClickhouseScriptTypeAnnotation(
    val expression: Expression?,
    val diagnostics: ClickhouseSemanticDiagnostics,
)

/**
 * Explicit opt-in to sequential analysis of [script] within one session, assuming preceding
 * statements execute successfully. Never selected automatically from semicolons or editor
 * file contents. Each call starts with unknown external state and returns independently owned
 * statement ASTs. Query-local SETTINGS overrides do not leak. Unsupported/reset/unresolved
 * mutations use the same unknown-state policy as script diagnostics; inline INSERT payload
 * is never interpreted as SET. Schema/connection state is not changed or inferred from DDL.
 *
 * Existing [annotateClickhouseSemanticTypes] remains statement/AST-local. This entrypoint
 * parses shared safe statement units; parser/framing failures have no AST and keep their
 * positioned reports. No authored settings, SQL text or source metadata are rewritten.
 * Use exactly the researched engine pin and a ClickHouse-specific [schema].
 */
fun annotateClickhouseScriptSemanticTypes(
    script: String,
    engineVersion: String,
    schema: Schema? = null,
    checkCanceled: () -> Unit = {},
): List<ClickhouseScriptTypeAnnotation> {
    require(engineVersion == "26.10.2.103") { "Native semantic typing requires the researched ClickHouse pin 26.10.2.103" }
    require(schema == null || schema.dialect?.name == "clickhouse") { "Expected a ClickHouse schema" }
    checkCanceled()
    return clickhouseScriptSemanticStatements(script, engineVersion, schema, checkCanceled) { expression, settings, report ->
        val typed = expression?.let {
            annotateClickhouseSemanticTypesWithSettings(it, engineVersion, schema, settings, checkCanceled)
        }
        ClickhouseScriptTypeAnnotation(typed, report)
    }
}
