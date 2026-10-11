package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.Test
import kotlin.test.assertTrue

class TargetTypingDependencyTest {
    @Test
    fun targetNumericHelpersPreserveUnknownsAndDialectCoercionEvidence() {
        for ((ref, source, expected) in listOf(
            Triple(AnnotatorRef.NumericArg,"FLOOR(CAST(1 AS DECIMAL(8,2)))",DType.DECIMAL),
            Triple(AnnotatorRef.NumericArg,"FLOOR(missing)",DType.UNKNOWN),
            Triple(AnnotatorRef.IntAsDouble,"FLOOR(CAST(1 AS BIGINT))",DType.DOUBLE),
            Triple(AnnotatorRef.IntAsDouble,"FLOOR(missing)",DType.UNKNOWN),
            Triple(AnnotatorRef.MathPostgres,"FLOOR(CAST(1 AS FLOAT))",DType.DOUBLE),
            Triple(AnnotatorRef.FloorCeilDuckdb,"FLOOR(CAST(1 AS DECIMAL(8,2)))",DType.UNKNOWN),
            Triple(AnnotatorRef.FloorCeilHive,"FLOOR(1.2)",DType.UNKNOWN),
            Triple(AnnotatorRef.FloorCeilHive,"FLOOR(CAST(1 AS BIGINT))",DType.BIGINT),
        )) {
            val tree = Dialects.BASE.parseOne(source)
            annotateTypes(tree, expressionMetadata = Dialects.BASE.expressionMetadata + mapOf(Floor::class to TypingSpec.Annotate(ref)))
            assertTrue(tree.isType(expected), "$ref/$source/${tree.type}")
        }
    }

    @Test
    fun duckdbZoneAndTruncationHelpersKeepActualTemporalKinds() {
        for ((source, expected) in listOf("DATE '2024-01-01' AT TIME ZONE 'UTC'" to DType.TIMESTAMPTZ,
            "TIMESTAMPTZ '2024-01-01 00:00:00+00' AT TIME ZONE 'UTC'" to DType.TIMESTAMP,
            "missing AT TIME ZONE 'UTC'" to DType.UNKNOWN)) {
            val tree = Dialects.DUCKDB.parseOne(source)
            annotateTypes(tree, dialect = Dialects.DUCKDB, expressionMetadata = Dialects.DUCKDB.expressionMetadata +
                mapOf(AtTimeZone::class to TypingSpec.Annotate(AnnotatorRef.AtTimeZoneDuckdb)))
            assertTrue(tree.isType(expected))
        }
        val tree = Dialects.DUCKDB.parseOne("DATE_TRUNC('day', DATE '2024-01-01')")
        annotateTypes(tree, dialect = Dialects.DUCKDB, expressionMetadata = Dialects.DUCKDB.expressionMetadata +
            mapOf(DateTrunc::class to TypingSpec.Annotate(AnnotatorRef.DateTruncDuckdb),
                TimestampTrunc::class to TypingSpec.Annotate(AnnotatorRef.DateTruncDuckdb)))
        assertTrue(tree.isType(DType.TIMESTAMP))
    }
}
