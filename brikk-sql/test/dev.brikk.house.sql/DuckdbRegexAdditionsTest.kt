package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbRegexAdditionsTest {
    @Test fun nativeOverloadsFlagsAndInvalidAritiesRoundTripWithoutTruncationOrDefaultExpansion() {
        for(call in listOf("regexp_extract('ABC','abc','i')","regexp_extract('a','(a)',1,'l')","regexp_extract_all('a','(a)',['x'],'i')",
            "regexp_extract('x','(a)',['x'])","regexp_replace('aaa','a','x','g')","regexp_replace('aaa','a','x','k')",
            "regexp_extract('a','a',0,'e')","regexp_extract('a','a',0,'i',99)","regexp_replace('a','a','x','g',99)")) {
            val tree=Dialects.DUCKDB.parseOne("SELECT $call AS result")
            assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
            val sql=tree.sql("duckdb");assertEquals(sql,Dialects.DUCKDB.parseOne(sql).sql("duckdb"))
            assertTrue(SqlFragment(sql,"duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
            if("99" in call)assertTrue("99" in sql)
            if("'e'" in call)assertTrue("'e'" in sql)
            if("'l'" in call)assertTrue("'l'" in sql)
        }
        val sql="SELECT regexp_extract(?,?,?),regexp_extract_all(?,?,?,?),regexp_replace(?,?,?,?)"
        assertEquals(sql.indices.filter { sql[it]=='?' },parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        assertEquals(11,SqlFragment(sql,"duckdb").toExecutable("duckdb").sql.count { it=='?' })
    }
    @Test fun nativeShapesDistinguishScalarListStructAndListOfStructWithoutChangingTheAnnotationLedger() {
        for((call,expected,nullable)in listOf(Triple("regexp_extract(NULL,'a')","TEXT",true),
            Triple("regexp_extract('a','a',NULL)","TEXT",false),Triple("regexp_extract('ABC','abc','i')","TEXT",false),
            Triple("regexp_extract_all('a','a')","ARRAY<TEXT>",false),Triple("regexp_extract_all('a','a',NULL)","ARRAY<TEXT>",true))) {
            val column=SqlFragment("SELECT $call AS result","duckdb").outputShape().columns.single()
            assertEquals(expected,column.type,call);assertEquals(nullable,column.nullable,call)
            if(expected=="TEXT")assertEquals("VARCHAR",RelationScope.analyze("SELECT $call AS result","duckdb",RelationFacts()).output!!.columns.single().sqlType)
        }
        for((name,prefix)in listOf("regexp_extract" to "STRUCT", "regexp_extract_all" to "ARRAY<STRUCT")) {
            val sql="SELECT $name('a1','(a)(1)',['letter','digit']) AS result"
            val shape=SqlFragment(sql,"duckdb").outputShape().columns.single()
            assertTrue(shape.type.startsWith(prefix),shape.type);assertTrue("letter" in shape.type && "digit" in shape.type)
            val output=RelationScope.analyze(sql,"duckdb",RelationFacts()).output!!.columns.single()
            assertEquals(ColumnOrigin.COMPUTED,output.origin);assertNull(output.provenance)
        }
        for(call in listOf("regexp_extract_all('a','a',?)","regexp_extract_all('a','a',[])","regexp_extract_all('a','a',['x','x'])","custom.regexp_extract('a','a')"))
            assertNull(RelationScope.analyze("SELECT $call AS result","duckdb",RelationFacts()).output!!.columns.single().sqlType)
    }
    @Test fun newOverloadMappingsRefuseWithoutInvalidatingExistingOrdinaryMappings() {
        for(call in listOf("regexp_extract('ABC','abc','i')","regexp_extract('a','(a)',['x'])","regexp_extract_all('a','(a)',['x'])"))
            for(target in listOf("trino","postgres","doris","clickhouse","bigquery","mysql"))
                assertFailsWith<UnsupportedError> { SqlFragment("SELECT $call","duckdb").toExecutable(target) }
    }
}
