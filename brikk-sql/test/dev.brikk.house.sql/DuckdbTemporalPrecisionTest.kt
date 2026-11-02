package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.*

class DuckdbTemporalPrecisionTest {
    @Test fun nativeCastResultPrecisionIsPhysicalWhileTheAuthoredDestinationAndSerdeRemainIntact() {
        for(precision in 0..10) {
            val expected=when(precision) { 0->"TIMESTAMP_S";in 1..3->"TIMESTAMP_MS";in 4..6->"TIMESTAMP";else->"TIMESTAMP_NS" }
            for(function in listOf("CAST","TRY_CAST")) {
                val sql="SELECT $function('2024-01-01 00:00:00.123456789' AS TIMESTAMP($precision)) AS t"
                val tree=parseDuckdbNativeOne(sql)
                assertEquals(tree,Serde.load(Serde.dump(tree)))
                assertTrue("TIMESTAMP($precision)" in tree.copy().sql("duckdb"))
                assertEquals(expected,SqlFragment(sql,"duckdb").outputShape().columns.single().type,sql)
                assertEquals(expected,RelationScope.analyze(sql,"duckdb",RelationFacts()).output!!.columns.single().sqlType,sql)
            }
        }
        for(type in listOf("TIMESTAMP(11)","TIMESTAMP(-1)","TIMESTAMP(1.0)","TIMESTAMPTZ(9)","TIME(3)")) {
            val result=runCatching { SqlFragment("SELECT CAST('invalid' AS $type) AS t","duckdb").outputShape().columns.single().type }
            assertTrue(result.isFailure || result.getOrNull()=="UNKNOWN",type)
        }
        val caller=parseDuckdbNativeOne("SELECT CAST('2024-01-01' AS TIMESTAMP(9))")
        caller.findAll<Cast>().single().typeSlot=DataType.build(DType.VARCHAR)
        val retained=annotateTypes(caller,dialect=nativeDuckdbAnalysisDialect())
        assertEquals(DType.VARCHAR,retained.findAll<Cast>().single().type?.thisArg)
    }
}
