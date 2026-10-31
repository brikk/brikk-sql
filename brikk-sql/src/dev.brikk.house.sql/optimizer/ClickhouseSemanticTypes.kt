package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.dialects.Dialects

/**
 * Annotate the bounded native ClickHouse higher-order slice, including exact numeric
 * literal widths. Mutates [expression] like [annotateTypes]; types are conditional,
 * never acceptance/execution verdicts. An exact researched pin is mandatory.
 * Generic annotateTypes retains its existing literal policy for parity consumers.
 * Missing scopes, unsupported joins/overloads/settings stay unknown. [schema] must
 * be ClickHouse-specific; caller-authored type slots are overwritten by annotation.
 */
fun annotateClickhouseSemanticTypes(
    expression: Expression,
    engineVersion: String,
    schema: Schema? = null,
    checkCanceled: () -> Unit = {},
): Expression {
    require(engineVersion == "26.10.2.103") { "Native semantic typing requires the researched ClickHouse pin 26.10.2.103" }
    require(schema == null || schema.dialect?.name == "clickhouse") { "Expected a ClickHouse schema" }
    checkCanceled()
    return TypeAnnotator(schema ?: MappingSchema(dialect = Dialects.CLICKHOUSE)).also {
        it.clickhouseNativeLiterals = true
        it.clickhouseEngineVersion = engineVersion
        it.clickhouseCheckCanceled = checkCanceled
    }.annotate(expression)
}
