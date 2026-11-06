package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.generator.Generator
import kotlin.Boolean
import kotlin.String

private fun String?.isDuckdb(): Boolean = equals("duckdb", ignoreCase = true)

/** Node-level facts the executable guards need, gathered in one walk of the tree. */
private class DuckdbGuardFacts(expr: Expression) {
    var checkpoint = false
    var extensionManagement = false
    var externalResource = false
    var explain = false
    var nameShow = false
    var factorial = false
    var storageAttach = false
    var regexOverload = false
    var setval = false
    var show = false
    var pathFunction = false
    var overlay = false
    var malformedOverlay = false
    var secureView = false
    var recursiveView = false
    var trigger = false
    var temporary = false
    var namedParameter = false
    var variableSet = false
    var variant = false
    var jsonCall = false
    var groupingUnnest = false

    init {
        for (node in expr.walk()) {
            if (node.isDuckdbCheckpointCommand()) checkpoint = true
            if (node.isDuckdbExtensionManagement()) extensionManagement = true
            if (node.isDuckdbExternalResourceStatement() || node is DuckdbExternalResourceSource) externalResource = true
            if (node is DuckdbExplain) explain = true
            if (node.isDuckdbNameShow()) nameShow = true
            if (node is Factorial) factorial = true
            if (node.isDuckdbStorageAttach()) storageAttach = true
            if (node.hasDuckdbRegexOverload()) regexOverload = true
            if (node.isDuckdbSetval()) setval = true
            if (node is Show || node is Command && node.name.equals("SHOW", true)) show = true
            if (node.duckdbPathFunctionName() != null) pathFunction = true
            if (node.isDuckdbOverlayCall()) {
                overlay = true
                if (node.duckdbOverlayArguments() == null) malformedOverlay = true
            }
            if (node is SecureProperty) secureView = true
            if (node is DuckdbRecursiveViewProperty) recursiveView = true
            if (node.isTriggerDdl()) trigger = true
            if (node is TemporaryProperty) temporary = true
            if ((node is Parameter || node is Placeholder && node.thisArg != null) && node.name.any { it !in '0'..'9' })
                namedParameter = true
            if (node is SetItem && node.text("kind").equals("VARIABLE", ignoreCase = true)) variableSet = true
            if (node is DataType && node.isType(DType.VARIANT) || node.isDuckdbVariantFunction()) variant = true
            if (node.duckdbJsonCall() != null) jsonCall = true
            // Alias/ordinal grouping and ungrouped post-aggregate expansions need a proved
            // translation; an ordinary table-source UNNEST is not this operation.
            if (node is Explode || node is Unnest) {
                val select = node.findAncestor<Select>()
                if (select != null && select.args["group"] != null &&
                    generateSequence<Expression>(select) { it.parent }.any { it === expr } &&
                    generateSequence(node.parent) { it.parent }.takeWhile { it !== select }
                        .none { it is From || it is Join || it is Table || it is Lateral }) groupingUnnest = true
            }
        }
    }
}

/**
 * Executable boundaries for a non-DuckDB target: DuckDB-only constructs in the tree, and
 * DuckDB-sourced semantics with no verified translation. Runs from every non-DuckDB
 * generator, before target transforms rewrite the tree.
 */
internal fun Generator.checkDuckdbConstructsForForeignTarget(expr: Expression) {
    val fromDuckdb = sourceDialect.isDuckdb()
    val strict = duckdbNativeSemantics || forExecution
    if (strict) expr.duckdbDivisionTranslationIssue(sourceDialect, dialect.name)?.let(::unexecutable)
    if (strict) expr.duckdbExecutableBoundaryIssue(sourceDialect, dialect.name, forExecution)?.let(::unexecutable)
    if (strict && fromDuckdb && expr.hasDuckdbZoneOrCollation())
        unexecutable("DuckDB zone/calendar/collation and nanosecond semantics have no verified cross-target translation")
    val facts = DuckdbGuardFacts(expr)
    if (strict && fromDuckdb && facts.checkpoint)
        unexecutable("DuckDB CHECKPOINT storage control has no verified cross-target translation")
    if (expr.hasKeyedCte()) unexecutable("$targetName has no verified recursive USING KEY translation")
    if (facts.extensionManagement)
        unexecutable("$targetName has no verified DuckDB extension/repository management translation")
    if (facts.externalResource)
        unexecutable("$targetName has no verified DuckDB external-resource lifecycle translation")
    if (facts.explain) unexecutable("$targetName has no verified DuckDB EXPLAIN option/plan translation")
    if (facts.nameShow) unexecutable("DuckDB SHOW name/query resolution has no verified cross-target translation")
    if (fromDuckdb && facts.factorial)
        unexecutable("DuckDB factorial input, HUGEINT result and error semantics have no verified cross-target translation")
    if (fromDuckdb && (expr.hasDuckdbSnapshot() || facts.storageAttach))
        unexecutable("DuckDB extension attachment/snapshot semantics have no verified cross-target translation")
    if (fromDuckdb) checkDuckdbFeatureContracts(expr, facts)
    if (fromDuckdb && facts.overlay)
        unexecutable("$targetName has no verified DuckDB OVERLAY offset/count/Unicode translation")
    // Among the implemented targets only DuckDB has a verified secure-view contract. An
    // origin-less AST is not permission to emit an unsafe DDL.
    if (facts.secureView) unexecutable("$targetName has no verified DuckDB SECURE VIEW information-hiding translation")
    if (facts.recursiveView) unexecutable("$targetName has no verified DuckDB recursive view translation")
    if (!fromDuckdb) return
    if (facts.trigger) unexecutable("$targetName has no verified DuckDB trigger translation")
    if (duckdbSessionDefaults == true || duckdbSessionDefaults == null && facts.namedParameter || facts.variableSet)
        unexecutable("$targetName has no verified DuckDB session-variable/default translation")
    if (facts.variant) unexecutable("$targetName has no verified DuckDB VARIANT translation")
    if (facts.jsonCall) unexecutable("$targetName has no verified DuckDB JSON mutation/normalization translation")
    // Checked before target SELECT transforms move projection UNNESTs into FROM.
    if (facts.groupingUnnest) unexecutable("$targetName has no verified DuckDB grouping UNNEST translation")
}

/** Executable boundaries for the DuckDB target. A null source is native, not a translation. */
internal fun DuckdbGenerator.checkDuckdbTargetBoundaries(expr: Expression) {
    val fromOther = sourceDialect != null && !sourceDialect.isDuckdb()
    if (duckdbNativeSemantics || forExecution) {
        expr.duckdbDivisionTranslationIssue(sourceDialect, dialect.name)?.let(::unexecutable)
        expr.duckdbExecutableBoundaryIssue(sourceDialect, dialect.name, forExecution)?.let(::unexecutable)
    }
    val facts = DuckdbGuardFacts(expr)
    if (fromOther) {
        checkDuckdbFeatureContracts(expr, facts)
        if (facts.overlay) unexecutable("DuckDB OVERLAY is not a verified same-name translation from $sourceDialect")
    }
    if (facts.malformedOverlay) unexecutable("DuckDB OVERLAY has no valid modeled three/four-argument signature")
    if (fromOther && facts.secureView)
        unexecutable("DuckDB SECURE VIEW is not a verified same-keyword translation from $sourceDialect")
    if (fromOther && facts.trigger)
        unexecutable("DuckDB trigger semantics are not a verified translation from $sourceDialect")
    if (expr.isTriggerDdl() && facts.temporary) unexecutable("DuckDB 2.0 does not support temporary triggers")
    if (fromOther && facts.jsonCall)
        unexecutable("DuckDB JSON mutation/normalization is not a verified same-name translation from $sourceDialect")
    if (fromOther && facts.variant) unexecutable("DuckDB VARIANT is not a verified translation from $sourceDialect")
}

/** DuckDB feature contracts that hold in neither translation direction: into DuckDB or out of it. */
private fun Generator.checkDuckdbFeatureContracts(expr: Expression, facts: DuckdbGuardFacts) {
    val toDuckdb = dialect.name.isDuckdb()
    if (expr.hasDuckdbParquetContract())
        unexecutable("DuckDB Parquet schema/virtual columns have no verified cross-target translation")
    if (expr.hasDuckdbParquetWriteContract())
        unexecutable("DuckDB Parquet writer encoding options have no verified cross-target translation")
    if (expr.hasDuckdbGeoJsonContract())
        unexecutable("DuckDB GeoJSON Feature/geometry semantics have no verified cross-target translation")
    if (facts.regexOverload) unexecutable("DuckDB regex options/name-list overload has no verified cross-target translation")
    if (facts.setval) unexecutable("DuckDB SETVAL sequence-state behavior has no verified cross-target translation")
    if (toDuckdb && facts.show)
        unexecutable("DuckDB SHOW is not a verified setting/table resolution translation from $sourceDialect")
    if (expr.hasDuckdbCopyLayout())
        unexecutable("DuckDB COPY PARTITION_BY/PARTITION_PATH/ORDER_BY has no verified cross-target file-layout translation")
    if (facts.pathFunction)
        unexecutable("DuckDB path helpers have no verified cross-target escaping/normalization translation")
}
