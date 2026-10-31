package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbDivisionPolicyTest {
    @Test fun nativeOperatorsCopySerdeAndBindOrderDoNotAcquireNullIfOrConditionalRewrites() {
        val sql="SELECT CAST(? AS INTEGER) // CAST(? AS INTEGER),CAST(? AS INTEGER) % CAST(? AS INTEGER),? / ?"
        val tree=parseDuckdbNativeOne(sql)
        assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
        val generated=SqlFragment(sql,"duckdb").toExecutable("duckdb")
        assertEquals(6,generated.sql.count { it=='?' });assertEquals(generated.sql,assertNotNull(generated.sourceMap).output)
        assertFalse("NULLIF" in generated.sql || "CASE" in generated.sql || "TRY(" in generated.sql)
        assertEquals(sql.indices.filter { sql[it]=='?' },parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        val generator=Dialects.DUCKDB.generator(sourceDialect="postgres").apply { forExecution=true;trackSpans=true }
        generator.generate(Dialects.POSTGRES.parseOne("SELECT 1"));assertNotNull(generator.lastSourceMap)
        assertFailsWith<UnsupportedError> { generator.generate(Dialects.POSTGRES.parseOne("SELECT x / y FROM t")) }
        assertNull(generator.lastSourceMap)
    }
    @Test fun nativeTypeAndNullabilityFactsDoNotConfuseFloatingDivisionWithNullableIntegerZeroPolicy() {
        for((body,type,nullable)in listOf(Triple("7 / 0","DOUBLE",false),Triple("7 // 2","INT",false),
            Triple("7 % 2","INT",false),Triple("7 // 0","INT",null),Triple("7 % 0","INT",null),
            Triple("NULL // 0","INT",true),Triple("NULL // NULL","BIGINT",true),Triple("TRY(7 // 0)","INT",true),
            Triple("CAST(5 AS FLOAT) // CAST(2 AS FLOAT)","FLOAT",null),
            Triple("CAST(5 AS DECIMAL(8,2)) / CAST(2 AS DECIMAL(8,2))","DOUBLE",false),
            Triple("CAST(5 AS DECIMAL(8,2)) % CAST(2 AS DECIMAL(8,2))","DECIMAL(8, 2)",null))) {
            val sql="SELECT $body AS q"
            val shape=SqlFragment(sql,"duckdb").outputShape().columns.single()
            assertEquals(type,shape.type,body);assertEquals(nullable,shape.nullable,body)
            assertEquals(type,RelationScope.analyze(sql,"duckdb",RelationFacts()).output!!.columns.single().sqlType,body)
        }
        assertEquals("UNKNOWN",SqlFragment("SELECT 5.0 % 2 AS q","duckdb").outputShape().columns.single().type)
        assertEquals("UNKNOWN",SqlFragment("SELECT 9223372036854775807 // 1 AS q","duckdb").outputShape().columns.single().type)
        assertEquals("UNKNOWN",SqlFragment("SELECT CAST(1 AS FLOAT) / 0.1 AS q","duckdb").outputShape().columns.single().type)
    }
    @Test fun hazardousCrossTargetAndSafeLoweringsRefuseWithoutBreakingLegacySyntaxControls() {
        for(target in listOf("trino","postgres","doris","clickhouse","bigquery","mysql"))
            for(body in listOf("7 // 0","7 % 0","7 / 0","x / y"))
                assertFailsWith<UnsupportedError> { SqlFragment("SELECT $body","duckdb").toExecutable(target) }
        for(sql in listOf("SELECT CAST(7 AS BIGINT) / CAST(0 AS BIGINT)","SELECT x / y FROM t","SELECT x / 2 FROM t","SELECT 7.0 / 2"))
            assertFailsWith<UnsupportedError> { SqlFragment(sql,"postgres").toExecutable("duckdb") }
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT SAFE_DIVIDE(?,?)","bigquery").toExecutable("duckdb") }
        assertTrue(SqlFragment("SELECT SAFE_DIVIDE(?,?)","bigquery").transpileTo("duckdb").unsupportedMessages.any { "one-time" in it })
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT SAFE_DIVIDE(1,0)","duckdb").toExecutable("duckdb") }
        // Legacy syntax/AST gates are not native capability certification.
        assertTrue(Dialects.DUCKDB.generator().safeIntDivision)
        val source="SELECT CAST(7 AS BIGINT) / CAST(2 AS BIGINT)"
        assertTrue("TRUNC" in SqlFragment(source,"postgres").transpileTo("duckdb").sql)
        assertTrue("//" in SqlFragment(source,"postgres").toExecutable("duckdb").sql)
        assertTrue("//" in SqlFragment("SELECT 7 / 2","postgres").toExecutable("duckdb").sql)
        assertTrue(SqlFragment("SELECT 7.0 / 2","duckdb").transpileTo("trino").unsupportedMessages.isEmpty())
    }
}
