package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbSetvalTest {
    @Test fun existingCallsPreserveSpellingArgumentOrderAndExactParameters() {
        for(call in listOf("setval('main.s',42)","setval('s',42,false)","setval('s',42,NULL)","setval(value:=42,sequence_name:='s',is_called:=false)")) {
            val tree=Dialects.DUCKDB.parseOne("SELECT $call AS result")
            assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
            val sql=tree.sql("duckdb");assertEquals(sql,Dialects.DUCKDB.parseOne(sql).sql("duckdb"))
            assertTrue(SqlFragment(sql,"duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        }
        val sql="SELECT setval(?,?,?) AS result"
        assertEquals(sql.indices.filter { sql[it]=='?' },parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        assertEquals(3,SqlFragment(sql,"duckdb").toExecutable("duckdb").sql.count { it=='?' })
    }
    @Test fun bigintAndStrictNullContractsAreStaticButSequenceStateAndRewritingRefuse() {
        for((call,nullable)in listOf("setval('s',42)" to false,"setval('s',42,false)" to false,"setval(NULL,42)" to true,
            "setval('s',NULL)" to true,"setval('s',42,NULL)" to true,"setval(value:=42,sequence_name:='s')" to false)) {
            val sql="SELECT $call AS result"
            val fragment=SqlFragment(sql,"duckdb")
            val shape=fragment.outputShape().columns.single()
            assertEquals("BIGINT",shape.type,call);assertEquals(nullable,shape.nullable,call)
            val scope=RelationScope.analyze(sql,"duckdb",RelationFacts()).output!!.columns.single()
            assertEquals(ColumnOrigin.COMPUTED,scope.origin);assertNull(scope.provenance)
            assertFails { qualify(fragment.ast.copy(),dialect=Dialects.DUCKDB) }
            assertFails { fragment.columnDependencies() }
        }
        for(call in listOf("custom.setval('s',42)","setval('s')","setval('s',1,TRUE,FALSE)"))
            assertNull(RelationScope.analyze("SELECT $call AS result","duckdb",RelationFacts()).output!!.columns.single().sqlType)
        for(target in listOf("postgres","trino","doris","clickhouse","mysql","bigquery"))
            assertFailsWith<UnsupportedError> { SqlFragment("SELECT setval('s',42)","duckdb").toExecutable(target) }
        assertFailsWith<UnsupportedError> { SqlFragment("SELECT setval('s',42)","postgres").toExecutable("duckdb") }
    }
}
