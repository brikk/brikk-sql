package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbShowSettingsTest {
    @Test fun namesAndQueriesStayShowNotDescribeAndSurviveSerde() {
        for(sql in listOf("SHOW threads","SHOW \"THREADS\"","SHOW 'threads'","SHOW main.threads","SHOW (SELECT ? AS x)","SHOW ''","SHOW \"TABLES\"")) {
            val tree=assertIs<Show>(Dialects.DUCKDB.parseOne(sql))
            assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
            val rendered=tree.sql("duckdb")
            assertTrue(rendered.startsWith("SHOW "));assertFalse("DESCRIBE" in rendered)
            assertEquals(rendered,Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
            assertTrue(SqlFragment(sql,"duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        }
        val script="SHOW 'a;b'; SHOW threads; SELECT 42;"
        assertEquals(3,SqlScriptSnapshot.parse(script,"duckdb").statements.count { it.hasSql })
        val sql="SHOW (SELECT ? AS x,\$value AS y)"
        assertEquals(listOf("?","\$value"),parseParameterOccurrences(sql,"duckdb").map { sql.substring(it.range.start,it.range.end) })
    }
    @Test fun ambiguousResultsAndMetadataLineageAreNotInvented() {
        for(sql in listOf("SHOW threads","SHOW main.threads","SHOW (SELECT 42 AS answer)")) {
            val fragment=SqlFragment(sql,"duckdb")
            val scope=RelationScope.analyze(sql,"duckdb",RelationFacts(catalog=CatalogKnowledge.CLOSED))
            assertTrue(scope.parsed);assertNull(scope.output);assertTrue(scope.references.isEmpty());assertTrue(scope.diagnostics.isEmpty())
            assertFails { fragment.outputShape() };assertFails { fragment.sourceTables };assertFails { fragment.columnDependencies() }
            assertFails { qualify(fragment.ast.copy(),dialect=Dialects.DUCKDB) }
            for(target in listOf("postgres","trino","doris","clickhouse","mysql","bigquery"))
                assertFailsWith<UnsupportedError> { fragment.toExecutable(target) }
        }
        assertEquals("SHOW TABLES",Dialects.DUCKDB.parseOne("SHOW TABLES").sql("duckdb"))
        assertEquals("SHOW ALL TABLES",Dialects.DUCKDB.parseOne("SHOW ALL TABLES").sql("duckdb"))
        assertFailsWith<UnsupportedError> { SqlFragment("SHOW threads","postgres").toExecutable("duckdb") }
    }
}
