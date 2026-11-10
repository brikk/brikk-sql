package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Merge
import dev.brikk.house.sql.ast.Update
import dev.brikk.house.sql.ast.hasKeyedAggregatePayload
import dev.brikk.house.sql.ast.hasKeyedCte
import dev.brikk.house.sql.ast.hasSideEffectingCte
import dev.brikk.house.sql.ast.isDuckdbExtensionManagement
import dev.brikk.house.sql.ast.isDuckdbExternalResourceStatement
import dev.brikk.house.sql.ast.isDuckdbRecursiveViewDefinition
import dev.brikk.house.sql.ast.isSecureViewDefinition
import dev.brikk.house.sql.ast.isTriggerDdl
import dev.brikk.house.sql.optimizer.Scope
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType

internal val Dialect.usesDuckdbNativeContracts: Boolean get() = this is DuckdbDialect

// Analysis passes' guards for native constructs: most return a refusal message; two return
// outcomes for handled statements, EXPLAIN, triggers, or alternative result shapes.
// Add new constructs to each pass's list here, with differences readable side by side;
// the per-pass lists are deliberately not yet unified.
// DuckDB introduced nearly all these constructs, hence the name; ungated checks also
// apply to every dialect (e.g. trigger DDL, secure views, UPDATE/MERGE lineage).
internal fun duckdbQualificationRefusal(expression: Expression, resolvedDialect: Dialect): String? {
    if (expression.hasDuckdbRegexSelector()) return "Runtime regex column-selector qualification is not modeled"
    if (resolvedDialect.usesDuckdbNativeContracts && expression.hasDuckdbSnapshot()) return "Snapshot qualification is not modeled"
    if (resolvedDialect.usesDuckdbNativeContracts && expression.hasDuckdbGeoJsonContract()) return "GeoJSON file/Feature rewriting is not modeled"
    if (resolvedDialect.usesDuckdbNativeContracts && expression.hasDuckdbParquetContract()) return "Parquet schema/virtual-column rewriting is not modeled; preserve the native scan"
    if (resolvedDialect.usesDuckdbNativeContracts && expression.walk().any { it.isDuckdbSetval() })
        return "Automatic rewriting across SETVAL sequence mutation is not modeled"
    if (expression.isDuckdbNameShow()) return "SHOW resolution requires runtime catalog/show_behavior, not SELECT qualification"
    if (resolvedDialect.usesDuckdbNativeContracts && expression.hasDuckdbCopyLayout())
        return "COPY layout options bind against output columns; automatic qualification is not modeled"
    if (expression is dev.brikk.house.sql.ast.DuckdbExplain) return "Qualify an EXPLAIN child explicitly; automatic wrapper rewriting is not modeled"
    if (expression.isDuckdbExternalResourceStatement()) return "External-resource options and handles are not SELECT qualification inputs"
    if (expression.isDuckdbExtensionManagement()) return "Extension/repository management is not a SELECT qualification input"
    if (expression.isDuckdbRecursiveViewDefinition()) return "Recursive view self-reference qualification is not modeled"
    if (expression.isTriggerDdl()) return "Trigger definitions are not SELECT optimizer inputs"
    if (expression is dev.brikk.house.sql.ast.SessionConnect || expression is dev.brikk.house.sql.ast.SessionDisconnect) {
        return "Session routing is not a query to qualify"
    }
    if (resolvedDialect.statefulCtes && expression.hasKeyedCte()) {
        return "Automatic qualification across recursive USING KEY state is not modeled"
    }
    if (resolvedDialect.statefulCtes && expression.hasSideEffectingCte()) {
        return "Automatic qualification across side-effecting CTEs is not modeled"
    }
    return null
}

internal fun duckdbColumnQualificationRefusal(expression: Expression): String? {
    if (expression.hasKeyedCte()) return "Automatic column qualification across recursive USING KEY state is not modeled"
    if (expression.isTriggerDdl()) return "Trigger definitions require their image/transition scope, not SELECT qualification"
    if (expression.isDuckdbExtensionManagement()) return "Extension/repository management has no query column scope"
    if (expression is dev.brikk.house.sql.ast.DuckdbExplain) return "EXPLAIN options are not query-column expressions"
    if (expression.isDuckdbExternalResourceStatement()) return "External-resource management has no query column scope"
    if (expression.isDuckdbRecursiveViewDefinition()) return "Recursive view self-reference qualification is not modeled"
    return null
}

internal fun duckdbTypeAnnotationRefusal(expression: Expression, dialect: Dialect): String? {
    if (expression is dev.brikk.house.sql.ast.DuckdbExplain) return "EXPLAIN returns plan text, not its child's expression types"
    if (expression.isDuckdbExternalResourceStatement()) return "External-resource statements require native handle/result contracts"
    // Keep simple INSTALL's published literal-annotation/serde behavior. The new
    // management nodes have runtime result contracts, not SELECT expression types.
    if (expression.isDuckdbExtensionManagement() && expression !is dev.brikk.house.sql.ast.Install)
        return "Extension/repository management requires its native result contract"
    if (expression.isDuckdbRecursiveViewDefinition()) return "Recursive view self-reference typing is not modeled"
    if (dialect.triggerScopes && expression.isTriggerDdl()) return "Trigger definitions require image/transition scope contracts"
    if (dialect.statefulCtes && expression.hasKeyedAggregatePayload()) {
        return "Recursive USING KEY aggregate types require the declarative CTE contract, not SELECT type annotation"
    }
    return null
}

internal fun duckdbLineageRefusal(expression: Expression, resolvedDialect: Dialect, scope: Scope?): String? {
    if (expression.hasDuckdbRegexSelector()) return "Runtime regex column-selector lineage is not modeled"
    if (resolvedDialect.usesDuckdbNativeContracts && expression.hasDuckdbSnapshot()) return "Snapshot/state lineage is not modeled"
    if (resolvedDialect.usesDuckdbNativeContracts && expression.hasDuckdbGeoJsonContract()) return "GeoJSON Feature/geometry/file lineage is not modeled"
    if (resolvedDialect.usesDuckdbNativeContracts && expression.hasDuckdbParquetContract()) return "Parquet file/field-ID/default lineage is not modeled"
    if (resolvedDialect.usesDuckdbNativeContracts && expression.walk().any { it.isDuckdbSetval() })
        return "SETVAL sequence-state lineage is not modeled"
    if (expression.isDuckdbNameShow()) return "SHOW metadata/setting output is not target query-result lineage"
    if (expression is dev.brikk.house.sql.ast.DuckdbExplain) return "EXPLAIN plan text has computed provenance, not child-result lineage"
    if (expression.isDuckdbExternalResourceStatement()) return "External-resource lifecycle state is not query-result lineage"
    if (expression.isDuckdbExtensionManagement()) return "Repository identity/fingerprints are runtime management results, not query-result lineage"
    if (expression.isSecureViewDefinition()) return "SECURE VIEW DDL has no query-result lineage; analyze its authored body separately"
    if (expression.isTriggerDdl()) return "Trigger DDL does not expose its body's query-result lineage"
    if (expression is dev.brikk.house.sql.ast.SessionConnect || expression is dev.brikk.house.sql.ast.SessionDisconnect) {
        return "Session routing does not expose query-result lineage"
    }
    if (expression.hasKeyedCte()) {
        return "Cannot infer recursive USING KEY aggregate-state lineage"
    }

    if (scope == null && (expression is dev.brikk.house.sql.ast.DuckdbPrepare || expression is dev.brikk.house.sql.ast.DuckdbExecute)) {
        return "Prepared/session statements do not expose a query result for lineage"
    }

    // buildScope(UPDATE/MERGE) can return a scalar/source query or FROM table. None is
    // the statement's output or assignment lineage. Refuse rather than trace the wrong root.
    if ((expression is Update || expression is Merge) && scope == null) {
        return "Cannot build ${expression.key.uppercase()} assignment lineage; sql must be a query"
    }
    return null
}

internal sealed interface DuckdbScopeAnalysisGuard {
    data class Refusal(val message: String) : DuckdbScopeAnalysisGuard
    data object Handled : DuckdbScopeAnalysisGuard
    data object Explain : DuckdbScopeAnalysisGuard
    data object Trigger : DuckdbScopeAnalysisGuard
}

internal fun duckdbScopeAnalysisGuard(node: Expression, dialect: Dialect): DuckdbScopeAnalysisGuard? {
    if (node.hasDuckdbRegexSelector()) return DuckdbScopeAnalysisGuard.Refusal("Runtime regex column-selector schemas are not modeled")
    if (node.isDuckdbNameShow()) return DuckdbScopeAnalysisGuard.Handled
    if (node is dev.brikk.house.sql.ast.DuckdbExplain) return DuckdbScopeAnalysisGuard.Explain
    if (node.isDuckdbExternalResourceStatement()) return DuckdbScopeAnalysisGuard.Handled
    if (node.isDuckdbExtensionManagement()) return DuckdbScopeAnalysisGuard.Handled
    if (node.isDuckdbRecursiveViewDefinition()) return DuckdbScopeAnalysisGuard.Refusal("Recursive view self-reference scope is not modeled")
    if (dialect.triggerScopes && node.isTriggerDdl()) return DuckdbScopeAnalysisGuard.Trigger
    if (node is dev.brikk.house.sql.ast.SessionConnect || node is dev.brikk.house.sql.ast.SessionDisconnect) return DuckdbScopeAnalysisGuard.Handled
    return null
}

internal fun duckdbTranspileStorageIssue(dialect: Dialect, sql: String): String? =
    if (dialect.usesDuckdbNativeContracts) duckdbStorageDdlIssue(sql) else null

internal fun duckdbSourceTablesRefusal(dialectObj: Dialect, sql: String, ast: () -> Expression): String? {
    if (dialectObj.usesDuckdbNativeContracts && (duckdbStorageDdlIssue(sql) != null || ast().hasDuckdbSnapshot())) return "Storage DDL/snapshot dependencies are not modeled"
    if (ast().isDuckdbNameShow()) return "SHOW target dependencies depend on runtime name resolution"
    return null
}

internal sealed interface DuckdbOutputShapeGuard {
    data class Refusal(val message: String) : DuckdbOutputShapeGuard
    data object DeclarativeCte : DuckdbOutputShapeGuard
    data object Explain : DuckdbOutputShapeGuard
    data object ExternalResource : DuckdbOutputShapeGuard
    data object Extension : DuckdbOutputShapeGuard
}

internal fun duckdbOutputShapeGuard(tree: Expression, sql: String, dialectObj: () -> Dialect): DuckdbOutputShapeGuard? {
    if (tree.hasDuckdbRegexSelector()) return DuckdbOutputShapeGuard.Refusal("Runtime regex column-selector result facts are not modeled")
    if (dialectObj().usesDuckdbNativeContracts && (duckdbStorageDdlIssue(sql) != null || tree.hasDuckdbSnapshot())) return DuckdbOutputShapeGuard.Refusal("Storage DDL/snapshot result facts are not modeled")
    if (dialectObj().usesDuckdbNativeContracts && (tree.hasDuckdbGeoJsonContract() || tree.walk().any { it.duckdbGeoJsonScalarType() != null })) return DuckdbOutputShapeGuard.DeclarativeCte
    if (dialectObj().usesDuckdbNativeContracts && tree.hasDuckdbParquetCall()) return DuckdbOutputShapeGuard.DeclarativeCte
    if (dialectObj().usesDuckdbNativeContracts && tree.walk().any { it.duckdbRegexName() != null }) return DuckdbOutputShapeGuard.DeclarativeCte
    if (dialectObj().usesDuckdbNativeContracts && tree.walk().any { it.isDuckdbSetval() }) return DuckdbOutputShapeGuard.DeclarativeCte
    if (tree.isDuckdbNameShow()) return DuckdbOutputShapeGuard.Refusal("SHOW output depends on runtime show_behavior/catalog; it is not the target query shape")
    if (tree is dev.brikk.house.sql.ast.DuckdbExplain) return DuckdbOutputShapeGuard.Explain
    if (tree.isDuckdbExternalResourceStatement()) return DuckdbOutputShapeGuard.ExternalResource
    if (tree.isDuckdbExtensionManagement()) return DuckdbOutputShapeGuard.Extension
    if (tree.isSecureViewDefinition()) return DuckdbOutputShapeGuard.Refusal("SECURE VIEW DDL has no query output shape; analyze its body separately")
    if (tree.isTriggerDdl()) return DuckdbOutputShapeGuard.Refusal("Trigger DDL has no query output shape")
    if (tree is dev.brikk.house.sql.ast.SessionConnect || tree is dev.brikk.house.sql.ast.SessionDisconnect) {
        return DuckdbOutputShapeGuard.Refusal("Session routing has no query output shape")
    }
    if (dialectObj().statefulCtes && (tree.hasSideEffectingCte() || tree.hasKeyedCte())) return DuckdbOutputShapeGuard.DeclarativeCte
    return null
}

internal fun duckdbParameterBindingRefusal(source: Dialect, statements: List<Expression>, tokens: List<Token>): String? {
    if (source.usesDuckdbNativeContracts && statements.single().isDuckdbCheckpointCommand() && tokens.any { it.tokenType in setOf(TokenType.PLACEHOLDER, TokenType.PARAMETER, TokenType.COLON) })
        return "Parameter binding in native CHECKPOINT commands is not modeled"
    if (statements.single() is dev.brikk.house.sql.ast.DuckdbExplain && statements.single().thisArg is dev.brikk.house.sql.ast.Command &&
        tokens.any { it.tokenType in setOf(TokenType.PLACEHOLDER, TokenType.PARAMETER, TokenType.COLON) })
        return "Parameter binding in a raw EXPLAIN child is not modeled"
    return null
}
