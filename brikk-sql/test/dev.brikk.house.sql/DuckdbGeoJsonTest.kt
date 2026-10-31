package dev.brikk.house.sql
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbGeoJsonTest {
    @Test fun formatsColumnSelectionNullUnsetAndGeometryCastsRoundTripWithoutJsonEmulation() {
        for(sql in listOf("COPY places TO 'never-written.geojson' (FORMAT GEOJSON,ID_COLUMN id,GEOMETRY_COLUMN geom,BBOX true)",
            "COPY places TO 'never-written.geojsonl' (FORMAT GEOJSONL,ARRAY true,ID_COLUMN NULL,GEOMETRY_COLUMN NULL)",
            "COPY places FROM 'never-read.geojson' (FORMAT GEOJSON)",
            "SELECT ST_AsGeoJSON(CAST('POINT(1 2)' AS GEOMETRY)) AS json")) {
            val tree=Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
            val generated=tree.sql("duckdb");assertEquals(generated,Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
            assertTrue(SqlFragment(sql,"duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
            for(target in listOf("postgres","trino","doris","clickhouse","spark"))
                assertFailsWith<UnsupportedError> { SqlFragment(sql,"duckdb").toExecutable(target) }
        }
        val sql="COPY (SELECT CAST(? AS GEOMETRY) g,? AS id) TO (?) (FORMAT GEOJSONL,ID_COLUMN ?,GEOMETRY_COLUMN ?)"
        assertEquals(sql.indices.filter { sql[it]=='?' },parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        assertEquals(5,SqlFragment(sql,"duckdb").toExecutable("duckdb").sql.count { it=='?' })
    }
    @Test fun onlyExplicitNonHiveColumnsDeclareAReadShapeAndGeometryIsNotJsonOrText() {
        val sql="SELECT * FROM read_json('never-read.geojson',geojson:=true,columns:={geometry:'GEOMETRY',id:'INTEGER',name:'VARCHAR'},hive_partitioning:=false) AS p"
        val output=RelationScope.analyze(sql,"duckdb",RelationFacts()).output!!
        assertEquals(listOf("geometry","id","name"),output.columns.map { it.name })
        assertEquals(listOf("GEOMETRY","INT","TEXT"),output.columns.map { it.sqlType })
        assertTrue(output.columns.all { it.nullability==Nullability.UNKNOWN && it.provenance==null })
        assertEquals(listOf("geometry","id","name"),SqlFragment(sql,"duckdb").outputShape().columns.map { it.name })
        val complete=sql.replace("SELECT *","SELECT p.")
        assertTrue(RelationScope.complete(complete,"duckdb",RelationFacts(),complete.indexOf("p.")+2).items.any { it.name=="geometry" && it.type=="GEOMETRY" })
        for(call in listOf("read_json('never-read.geojson')","read_json('never-read.json',geojson:=true)","custom.read_json('none',columns:={geometry:'GEOMETRY'},hive_partitioning:=false)"))
            assertNull(RelationScope.analyze("SELECT geometry FROM $call","duckdb",RelationFacts()).output!!.columns.single().sqlType)
        assertFails { qualify(Dialects.DUCKDB.parseOne(sql),dialect=Dialects.DUCKDB) }
        assertFails { SqlFragment(sql,"duckdb").columnDependencies() }
    }
    @Test fun scalarConverterContractsUseNativeTypesAndStrictNullsWithoutInventingGeometryContents() {
        for((call,type,nullable)in listOf(Triple("ST_GeomFromGeoJSON(NULL)","GEOMETRY",true),
            Triple("ST_AsGeoJSON(CAST('POINT(1 2)' AS GEOMETRY))","JSON",false),Triple("ST_AsGeoJSON(NULL)","JSON",true),
            Triple("ST_AsText(CAST('POINT(1 2)' AS GEOMETRY))","TEXT",false))) {
            val shape=SqlFragment("SELECT $call AS value","duckdb").outputShape().columns.single()
            assertEquals(type,shape.type,call);assertEquals(nullable,shape.nullable,call)
        }
        assertNull(RelationScope.analyze("SELECT custom.ST_GeomFromGeoJSON('x') AS value","duckdb",RelationFacts()).output!!.columns.single().sqlType)
    }
}
