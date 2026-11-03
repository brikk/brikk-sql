package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbParquetSchemaTest {
    private val schema="MAP {0:{name:'renamed',type:'BIGINT',default_value:NULL},1:{name:'added',type:'UTINYINT',default_value:43}}"
    private fun scan(function:String="read_parquet",extra:String="")="$function('never-read.parquet',schema:=$schema$extra)"
    @Test fun schemaMapAndNamedArgumentsRoundTripAndDeclareOnlyTheirOutputFields() {
        for(function in listOf("read_parquet","parquet_scan")) {
            val sql="SELECT * FROM ${scan(function)} AS p"
            val tree=Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree,Serde.load(Serde.dump(tree)));assertEquals(tree,tree.copy())
            val generated=tree.sql("duckdb");assertEquals(generated,Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
            val scope=RelationScope.analyze(sql,"duckdb",RelationFacts())
            assertTrue(scope.diagnostics.isEmpty(),scope.diagnostics.toString())
            assertEquals(listOf("renamed","added"),scope.output!!.columns.map { it.name },tree.toString())
            assertEquals(listOf("BIGINT","UTINYINT"),scope.output!!.columns.map { it.sqlType })
            assertTrue(scope.output!!.columns.all { it.nullability==Nullability.UNKNOWN && it.provenance==null })
            assertEquals(listOf("renamed","added"),SqlFragment(sql,"duckdb").outputShape().columns.map { it.name })
        }
    }
    @Test fun virtualColumnsAreSelectableAndCompletableButHiddenFromStarAndDoNotLeakThroughDerivedStars() {
        val from=scan()
        val sql="SELECT p.*,p.file_row_group_number AS rg,p.file_row_number AS rn FROM $from AS p"
        val scope=RelationScope.analyze(sql,"duckdb",RelationFacts())
        assertTrue(scope.diagnostics.isEmpty(),scope.diagnostics.toString())
        assertEquals(listOf("renamed","added","rg","rn"),scope.output!!.columns.map { it.name })
        assertEquals(listOf("BIGINT","UTINYINT","UBIGINT","BIGINT"),scope.output!!.columns.map { it.sqlType })
        val text="SELECT p. FROM $from p"
        val items=RelationScope.complete(text,"duckdb",RelationFacts(),text.indexOf("p.")+2).items
        assertTrue(items.any { it.name=="file_row_group_number" && it.type=="UBIGINT" })
        val derived=RelationScope.analyze("SELECT file_row_group_number FROM (SELECT * FROM $from)p","duckdb",RelationFacts())
        assertTrue(derived.diagnostics.any { it.problem==ScopeProblem.MISSING_COLUMN })
        assertEquals(listOf("renamed","added","filename","file_row_number"),SqlFragment("SELECT * FROM ${scan(extra=",filename:=true,file_row_number:=true")}","duckdb").outputShape().columns.map { it.name })
        val renamed=RelationScope.analyze("SELECT p.file_row_group_number AS rg FROM $from AS p(file_row_group_number,other)","duckdb",RelationFacts())
        assertEquals("BIGINT",renamed.output!!.columns.single().sqlType) // The renamed physical field shadows the hidden UBIGINT field.
        val outer=RelationScope.analyze("SELECT p.file_row_group_number AS rg FROM (SELECT 1 AS id)t LEFT JOIN $from p ON false","duckdb",RelationFacts())
        assertEquals(Nullability.NULLABLE,outer.output!!.columns.single().nullability)
    }
    @Test fun unknownFilesDynamicSchemasShadowedNamesAndQualifiedUdfsDoNotInventFileFacts() {
        for(sql in listOf("SELECT file_row_group_number AS rg FROM read_parquet('never-read.parquet')",
            "SELECT file_row_group_number AS rg FROM custom.read_parquet('never-read.parquet')",
            "SELECT renamed AS rg FROM custom.read_parquet('never-read.parquet',schema:=$schema)",
            "SELECT file_row_group_number AS rg FROM read_parquet('never-read.parquet',schema:=\$schema)"))
            assertNull(RelationScope.analyze(sql,"duckdb",RelationFacts()).output!!.columns.single().sqlType,sql)
        val shadow="SELECT file_row_group_number FROM read_parquet('none',schema:=MAP {'x':{name:'file_row_group_number',type:'VARCHAR',default_value:NULL}})"
        assertEquals("TEXT",RelationScope.analyze(shadow,"duckdb",RelationFacts()).output!!.columns.single().sqlType)
        val sql="SELECT * FROM ${scan()}"
        assertFails { qualify(Dialects.DUCKDB.parseOne(sql),dialect=Dialects.DUCKDB) }
        assertFails { SqlFragment(sql,"duckdb").columnDependencies() }
        for(target in listOf("postgres","trino","doris","clickhouse","spark"))
            assertFailsWith<UnsupportedError> { SqlFragment(sql,"duckdb").toExecutable(target) }
    }
    @Test fun schemaDefaultsAndBoundSchemaAreNotEvaluatedOrConflatedAndBindRangesStayExact() {
        val sql="SELECT renamed FROM read_parquet(?,schema:=MAP {0:{name:'renamed',type:'BIGINT',default_value:?}}) WHERE renamed>?"
        assertEquals(sql.indices.filter { sql[it]=='?' },parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        assertEquals(3,SqlFragment(sql,"duckdb").toExecutable("duckdb").sql.count { it=='?' })
        val declared="SELECT renamed FROM read_parquet('none',schema:=MAP {0:{name:'renamed',type:'BIGINT',default_value:nextval('audit')}})"
        assertEquals("BIGINT",RelationScope.analyze(declared,"duckdb",RelationFacts()).output!!.columns.single().sqlType)
        assertEquals("BIGINT",RelationScope.analyze("SELECT id FROM read_parquet('none',bound_schema:={'id':'BIGINT'},hive_partitioning:=false)","duckdb",RelationFacts()).output!!.columns.single().sqlType)
        assertNull(RelationScope.analyze("SELECT id FROM read_parquet('none',bound_schema:=\$shape,hive_partitioning:=false)","duckdb",RelationFacts()).output!!.columns.single().sqlType)
    }
}
