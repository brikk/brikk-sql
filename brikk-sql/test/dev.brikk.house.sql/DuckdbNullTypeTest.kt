package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbNullTypeTest {
    @Test fun nativeNullFactsStayNullThroughDerivedTablesCtesAndSetOperationsWithoutChangingParity() {
        for(sql in listOf("SELECT NULL AS v","SELECT NULL AS v UNION ALL SELECT NULL",
            "WITH t AS (SELECT NULL AS v) SELECT v FROM t","SELECT v FROM (SELECT NULL AS v) t")) {
            val shape=SqlFragment(sql,"duckdb").outputShape().columns.single()
            assertEquals("NULL",shape.type,sql);assertEquals(true,shape.nullable,sql)
            val column=RelationScope.analyze(sql,"duckdb",RelationFacts()).output!!.columns.single()
            assertEquals("NULL",column.sqlType,sql);assertEquals(Nullability.NULLABLE,column.nullability,sql)
        }
        val mixed="SELECT NULL AS v UNION ALL SELECT TRUE"
        assertEquals("BOOLEAN",SqlFragment(mixed,"duckdb").outputShape().columns.single().type)
        assertEquals("BOOLEAN",RelationScope.analyze(mixed,"duckdb",RelationFacts()).output!!.columns.single().sqlType)
        val parity=annotateTypes(Dialects.DUCKDB.parseOne("SELECT NULL AS v"),dialect=Dialects.DUCKDB)
        assertTrue(assertIs<Expression>(parity.expressionsArg.single()).isType(DType.UNKNOWN))
        val native=annotateTypes(parseDuckdbNativeOne("SELECT NULL AS v"),dialect=nativeDuckdbAnalysisDialect())
        assertTrue(assertIs<Expression>(native.expressionsArg.single()).isType(DType.NULL))
    }
    @Test fun nativeCatalogNullTypeAndExplicitCastsAreNotReplacedWithIntegerOrUnknown() {
        val query="SELECT v FROM physical_nulls"
        val inputs=ShapeCatalog(tables=mapOf("physical_nulls" to Shape(listOf(ColumnShape("v","\"NULL\"",nullable=true)))))
        assertEquals("NULL",SqlFragment(query,"duckdb").outputShape(inputs).columns.single().type)
        val facts=RelationFacts(tables=mapOf("physical_nulls" to RelationFact(listOf(ColumnFact("v","\"NULL\"",nullability=Nullability.NULLABLE)))))
        assertEquals("\"NULL\"",RelationScope.analyze(query,"duckdb",facts).output!!.columns.single().sqlType)
        assertEquals("INT",SqlFragment("SELECT CAST(NULL AS INTEGER) AS v","duckdb").outputShape().columns.single().type)
        assertEquals(true,SqlFragment("SELECT CAST(NULL AS INTEGER) AS v","duckdb").outputShape().columns.single().nullable)
        val ddl="CREATE TABLE nulls AS SELECT NULL AS v"
        val rendered=SqlFragment(ddl,"duckdb").toExecutable("duckdb").sql
        assertTrue("NULL" in rendered);assertFalse("INTEGER" in rendered)
        assertEquals(rendered,Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
    }
}
