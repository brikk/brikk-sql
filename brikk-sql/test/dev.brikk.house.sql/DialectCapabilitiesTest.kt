package dev.brikk.house.sql

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.nativeDuckdbAnalysisDialect
import dev.brikk.house.sql.dialects.usesDuckdbNativeContracts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Each capability holds exactly for the dialects whose names the passes used to compare against. */
class DialectCapabilitiesTest {
    private val dialects: List<Dialect> = Dialects.NAMES.map(Dialects::forName).distinct() + nativeDuckdbAnalysisDialect()

    private val capabilities: Map<String, Pair<(Dialect) -> Boolean, Set<String>>> = mapOf(
        "variantAccessYieldsVariant" to ({ d: Dialect -> d.variantAccessYieldsVariant } to setOf("duckdb")),
        "triggerScopes" to ({ d: Dialect -> d.triggerScopes } to setOf("duckdb")),
        "statefulCtes" to ({ d: Dialect -> d.statefulCtes } to setOf("duckdb")),
        "usesDuckdbNativeContracts" to ({ d: Dialect -> d.usesDuckdbNativeContracts } to setOf("duckdb")),
        "dmlScopes" to ({ d: Dialect -> d.dmlScopes } to setOf("duckdb")),
        "statementResultRows" to ({ d: Dialect -> d.statementResultRows } to setOf("duckdb")),
        "parametersDefaultToSessionVariables" to ({ d: Dialect -> d.parametersDefaultToSessionVariables } to setOf("duckdb")),
        "parametersFromSourceTokens" to ({ d: Dialect -> d.parametersFromSourceTokens } to setOf("duckdb")),
        "nestedCatalogPaths" to ({ d: Dialect -> d.nestedCatalogPaths } to setOf("duckdb")),
        "setOperationsIgnoreNullType" to ({ d: Dialect -> d.setOperationsIgnoreNullType } to setOf("duckdb")),
        "usingColumnTakesJoinSide" to ({ d: Dialect -> d.usingColumnTakesJoinSide } to setOf("duckdb")),
        "lambdaNamesKeepCase" to ({ d: Dialect -> d.lambdaNamesKeepCase } to setOf("duckdb")),
        "preservesWholeRowReferences" to ({ d: Dialect -> d.preservesWholeRowReferences } to setOf("duckdb")),
        "integerDivisionByZeroYieldsNull" to ({ d: Dialect -> d.integerDivisionByZeroYieldsNull } to setOf("duckdb")),
        "unnestDepthOptions" to ({ d: Dialect -> d.unnestDepthOptions } to setOf("duckdb")),
        "requiresConnectTarget" to ({ d: Dialect -> d.requiresConnectTarget } to setOf("duckdb")),
        "executesSessionStatements" to ({ d: Dialect -> d.executesSessionStatements } to setOf("duckdb")),
        "supportsMergeInsertByName" to ({ d: Dialect -> d.supportsMergeInsertByName } to setOf("duckdb")),
        "executesExtensionManagement" to ({ d: Dialect -> d.executesExtensionManagement } to setOf("duckdb")),
        "executesExternalResources" to ({ d: Dialect -> d.executesExternalResources } to setOf("duckdb")),
        "executesExplainOptions" to ({ d: Dialect -> d.executesExplainOptions } to setOf("duckdb")),
        "executesBinaryLiterals" to ({ d: Dialect -> d.executesBinaryLiterals } to setOf("duckdb")),
        "executesRegexOperators" to ({ d: Dialect -> d.executesRegexOperators } to setOf("duckdb")),
        "executesRecursiveViews" to ({ d: Dialect -> d.executesRecursiveViews } to setOf("duckdb")),
        "supportsTupleAggregateCombinators" to ({ d: Dialect -> d.supportsTupleAggregateCombinators } to setOf("clickhouse")),
        "contextualTypeInference" to ({ d: Dialect -> d.contextualTypeInference } to setOf("clickhouse")),
        "nullableTupleFields" to ({ d: Dialect -> d.nullableTupleFields } to setOf("clickhouse")),
        "jsonValueDefaultsToText" to ({ d: Dialect -> d.jsonValueDefaultsToText } to setOf("postgres", "trino")),
        "jsonReturningTypes" to ({ d: Dialect -> d.jsonReturningTypes } to setOf("trino")),
        "parseToVariantFunctions" to ({ d: Dialect -> d.parseToVariantFunctions } to setOf("doris")),
        "columnSlotsIgnoreCase" to ({ d: Dialect -> d.columnSlotsIgnoreCase } to setOf("doris")),
        "preservesFullUsingStar" to ({ d: Dialect -> d.preservesFullUsingStar } to setOf("starrocks")),
        "arrowTypes" to ({ d: Dialect -> d.arrowTypes } to setOf("datafusion", "spice")),
        "relationNamesFollowNormalization" to ({ d: Dialect -> d.relationNamesFollowNormalization } to setOf("datafusion", "spice")),
        "aliasColumnsNeedKnownWidth" to ({ d: Dialect -> d.aliasColumnsNeedKnownWidth } to setOf("datafusion", "spice")),
        "typesValuesColumns" to ({ d: Dialect -> d.typesValuesColumns } to setOf("datafusion", "spice")),
        "nestedStructFields" to ({ d: Dialect -> d.nestedStructFields } to setOf("datafusion", "spice")),
        "largeUtf8LengthIsBigint" to ({ d: Dialect -> d.largeUtf8LengthIsBigint } to setOf("datafusion")),
        "bundledFunctionExtensions" to ({ d: Dialect -> d.bundledFunctionExtensions } to setOf("spice")),
        "builtinTableFunctions" to ({ d: Dialect -> d.builtinTableFunctions } to setOf("spice")),
    )

    @Test
    fun capabilitiesMatchTheDialectNamesTheyReplace() {
        for ((capability, entry) in capabilities) {
            val (read, names) = entry
            for (dialect in dialects) assertEquals(dialect.name in names, read(dialect), "$capability for '${dialect.name}'")
        }
    }

    @Test
    fun nativeTypeSpellingsBelongToTheirDialects() {
        assertEquals(DType.NULL, nativeDuckdbAnalysisDialect().parseNativeType(" \"NULL\" ")?.thisArg)
        assertNull(Dialects.DUCKDB.parseNativeType("\"NULL\""))
        for (dialect in listOf(Dialects.DATAFUSION, Dialects.SPICE)) assertEquals(DType.BIGINT, dialect.parseNativeType("Int64")?.thisArg)
        for (dialect in dialects.filter { it.name !in setOf("datafusion", "spice", "duckdb") }) assertNull(dialect.parseNativeType("Int64"))
    }
}
