package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.shape.SqlFragment
import kotlinx.serialization.json.*
import org.duckdb.DuckDBConnection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

internal class DuckdbGeoJsonResultTest {
    private fun render(sql:String)=assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql)))).sql("duckdb")
    private fun quote(path:Path)="'${path.toAbsolutePath().toString().replace("'","''")}'"
    private fun connect()=DriverManager.getConnection("jdbc:duckdb:",Properties().apply { setProperty("autoload_known_extensions","false");setProperty("autoinstall_known_extensions","false") }) as DuckDBConnection
    private fun Connection.exec(sql:String)=createStatement().use { s -> if(s.execute(sql))s.resultSet.use { while(it.next()){} };Unit }
    private fun Connection.rows(sql:String)=createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while(r.next())add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun fixture(c:Connection) {
        c.exec("SET threads=1");c.exec("CREATE TABLE places(id INTEGER,name VARCHAR,geom GEOMETRY)")
        c.exec("INSERT INTO places VALUES(1,'alpha','POINT(1 2)'),(2,'beta','LINESTRING(0 0,1 1)'),(3,NULL,NULL)")
    }
    private fun scratch(block:(Path)->Unit) {
        val root=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"),"opencode")),"duckdb-geojson-")
        try { block(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun features(path:Path,collection:Boolean):List<JsonObject> = if(collection)Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("features").jsonArray.map { it.jsonObject }
        else Files.readAllLines(path).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    @Test fun formatsArrayOverridesAndCopyFromRoundTripWithJsonLoadedAndSpatialAbsent()=scratch { root ->
        for(generated in listOf(false,true))connect().use { c ->
            fixture(c)
            assertEquals(listOf(listOf("true")),c.rows("SELECT loaded FROM duckdb_extensions() WHERE extension_name='json'"))
            assertEquals(listOf(listOf("0")),c.rows("SELECT count(*) FROM duckdb_extensions() WHERE extension_name='spatial' AND loaded"))
            for((index,case)in listOf("GEOJSON" to true,"GEOJSONL" to false,"GEOJSON,ARRAY false" to false,"GEOJSONL,ARRAY true" to true).withIndex()) {
                val file=root.resolve("format-$generated-$index.tmp")
                val sql="COPY (SELECT * FROM places ORDER BY id) TO ${quote(file)} (FORMAT ${case.first})"
                c.exec(if(generated)render(sql)else sql)
                val values=features(file,case.second);assertEquals(3,values.size)
                assertTrue(values.all { it.getValue("type").jsonPrimitive.content=="Feature" })
                assertEquals("1",values[0].getValue("id").jsonPrimitive.content)
                assertFalse("id" in values[0].getValue("properties").jsonObject)
                assertEquals(JsonNull,values[2].getValue("geometry"))
                val read="SELECT id,name,ST_AsText(geometry) FROM read_json(${quote(file)},geojson:=true) ORDER BY id"
                val expected=listOf(listOf("1","alpha","POINT (1 2)"),listOf("2","beta","LINESTRING (0 0, 1 1)"),listOf("3",null,null))
                assertEquals(expected,c.rows(read));assertEquals(expected,c.rows(render(read)))
                c.exec("CREATE OR REPLACE TABLE restored(geometry GEOMETRY,id INTEGER,name VARCHAR)")
                val input="COPY restored FROM ${quote(file)} (FORMAT GEOJSON,GEOJSON true)"
                c.exec(if(generated)render(input)else input)
                assertEquals(expected,c.rows("SELECT id,name,ST_AsText(geometry) FROM restored ORDER BY id"))
            }
            assertEquals(listOf(listOf("false","false")),c.rows("SELECT current_setting('autoload_known_extensions'),current_setting('autoinstall_known_extensions')"))
        }
    }
    @Test fun featureIdGeometrySelectionNullUnsetsAndBoundingBoxesRemainNative()=scratch { root ->
        for(generated in listOf(false,true))connect().use { c ->
            fixture(c)
            val selected=root.resolve("selected-$generated.geojsonl")
            val sql="COPY (SELECT id,name,geom,CAST('POINT(9 8)' AS GEOMETRY) alt FROM places ORDER BY id) TO ${quote(selected)} (FORMAT GEOJSONL,ID_COLUMN name,GEOMETRY_COLUMN alt,BBOX true)"
            c.exec(if(generated)render(sql)else sql)
            val first=features(selected,false).first()
            assertEquals("alpha",first.getValue("id").jsonPrimitive.content)
            assertEquals("Point",first.getValue("geometry").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(listOf("9.0","8.0","9.0","8.0"),first.getValue("bbox").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("1",first.getValue("properties").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("Point",first.getValue("properties").jsonObject.getValue("geom").jsonObject.getValue("type").jsonPrimitive.content)
            val unset=root.resolve("unset-$generated.geojsonl")
            val unlocated="COPY (SELECT * FROM places ORDER BY id) TO ${quote(unset)} (FORMAT GEOJSONL,ID_COLUMN NULL,GEOMETRY_COLUMN NULL)"
            c.exec(if(generated)render(unlocated)else unlocated)
            val value=features(unset,false).first()
            assertFalse("id" in value);assertEquals(JsonNull,value.getValue("geometry"));assertTrue("geom" in value.getValue("properties").jsonObject)
        }
    }
    @Test fun readOptionsExtensionsRawDocumentsExplicitColumnsAndGeometryMetadataStayDistinct()=scratch { root ->
        connect().use { c ->
            fixture(c)
            val geo=root.resolve("features.geojson")
            c.exec(render("COPY places TO ${quote(geo)} (FORMAT GEOJSON)"))
            val json=root.resolve("same.json");Files.copy(geo,json)
            assertEquals(listOf(listOf("3")),c.rows(render("SELECT count(*) FROM read_json(${quote(geo)})")))
            assertEquals(listOf(listOf("1")),c.rows(render("SELECT count(*) FROM read_json(${quote(json)})")))
            assertEquals(listOf(listOf("3")),c.rows(render("SELECT count(*) FROM read_json(${quote(json)},geojson:=true)")))
            assertEquals(listOf(listOf("1")),c.rows(render("SELECT count(*) FROM read_json(${quote(geo)},geojson:=false)")))
            assertEquals(listOf(listOf("3")),c.rows(render("SELECT count(*) FROM ${quote(geo)}")))
            c.exec("CREATE TABLE imported(geometry GEOMETRY,id INTEGER,name VARCHAR)")
            c.exec(render("COPY imported FROM ${quote(geo)} (FORMAT GEOJSON)"))
            assertEquals(listOf(listOf("3")),c.rows("SELECT count(*) FROM imported"))
            c.exec("TRUNCATE imported")
            c.exec(render("COPY imported FROM ${quote(json)} (FORMAT GEOJSON)"))
            assertEquals(listOf(listOf(null,null,null)),c.rows("SELECT ST_AsText(geometry),id,name FROM imported"))
            c.exec("TRUNCATE imported")
            c.exec(render("COPY imported FROM ${quote(json)} (FORMAT GEOJSON,GEOJSON true)"))
            assertEquals(listOf(listOf("3")),c.rows("SELECT count(*) FROM imported"))
            val explicit="SELECT * FROM read_json(${quote(geo)},geojson:=true,columns:={geometry:'GEOMETRY',id:'INTEGER',name:'VARCHAR'},hive_partitioning:=false)"
            c.createStatement().use { s -> s.executeQuery(render(explicit)).use { r ->
                assertEquals(listOf("geometry","id","name"),(1..3).map { r.metaData.getColumnName(it) })
                assertEquals(listOf("GEOMETRY","INTEGER","VARCHAR"),(1..3).map { r.metaData.getColumnTypeName(it) })
            } }
            val collision=root.resolve("collision.geojsonl")
            Files.writeString(collision,"""{"type":"Feature","id":"FEATURE","geometry":{"type":"Point","coordinates":[1,2]},"properties":{"id":"PROPERTY","name":"a"}}"""+"\n")
            assertEquals(listOf(listOf("FEATURE","PROPERTY","a")),c.rows(render("SELECT id,id_1,name FROM read_json(${quote(collision)})")))
            val near=root.resolve("near.json")
            Files.writeString(near,"""{"g":{"type":"point","coordinates":[1,2]}}""")
            assertTrue(c.rows(render("SELECT typeof(g) FROM read_json(${quote(near)},geojson:=true)")).single().single()!!.startsWith("STRUCT"))
        }
    }
    @Test fun malformedGeometryIgnoreErrorsAndInvalidWriterSelectionsAreNotRepaired()=scratch { root ->
        connect().use { c ->
            fixture(c)
            val bad=root.resolve("malformed.geojsonl")
            Files.writeString(bad,listOf("""{"type":"Point","coordinates":[1,2]}""","""{"type":"Point","coordinates":["bad",2]}""","""{"type":"Point","coordinates":[5,6]}""").joinToString("\n",postfix="\n"))
            val sql="SELECT ST_AsText(geometry) FROM read_json(${quote(bad)})"
            assertFailsWith<SQLException> { c.rows(sql) };assertFailsWith<SQLException> { c.rows(render(sql)) }
            val ignored="SELECT count(*),count(geometry) FROM read_json(${quote(bad)},ignore_errors:=true)"
            assertEquals(listOf(listOf("3","2")),c.rows(render(ignored)))
            for((index,options)in listOf("FORMAT GEOJSON,ID_COLUMN missing","FORMAT GEOJSON,GEOMETRY_COLUMN name","FORMAT GEOJSON,ID_COLUMN geom",
                "FORMAT JSON,ID_COLUMN id","FORMAT JSON,GEOMETRY_COLUMN geom","FORMAT JSON,BBOX true","FORMAT GEOJSON,NONSENSE 1").withIndex()) {
                val file=root.resolve("must-not-write-$index.geojson")
                val copy="COPY places TO ${quote(file)} ($options)"
                assertFailsWith<SQLException> { c.exec(copy) };assertFailsWith<SQLException> { c.exec(render(copy)) }
                assertFalse(Files.exists(file))
            }
            val noGeom="COPY (SELECT 1 AS id) TO ${quote(root.resolve("no-geom.geojson"))} (FORMAT GEOJSON)"
            assertFailsWith<SQLException> { c.exec(render(noGeom)) }
        }
    }
    @Test fun scalarConvertersKeepJsonAndGeometryTypesNullsAndNativeErrors() {
        connect().use { c ->
            val sql="SELECT ST_AsGeoJSON(CAST('POINT(1 2)' AS GEOMETRY)),ST_GeomFromGeoJSON('{\"type\":\"Point\",\"coordinates\":[1,2]}'),ST_AsGeoJSON(NULL),ST_GeomFromGeoJSON(NULL)"
            for(text in listOf(sql,render(sql)))c.createStatement().use { s -> s.executeQuery(text).use { r ->
                assertEquals(listOf("JSON","GEOMETRY","JSON","GEOMETRY"),(1..4).map { r.metaData.getColumnTypeName(it) })
                assertTrue(r.next());assertNull(r.getObject(3));assertNull(r.getObject(4))
            } }
            assertEquals(listOf(listOf("POINT Z (1 2 3)")),c.rows(render("SELECT ST_AsText(ST_GeomFromGeoJSON(CAST('{\"type\":\"Point\",\"coordinates\":[1,2,3]}' AS JSON)))")))
            assertFailsWith<SQLException> { c.rows(render("SELECT ST_GeomFromGeoJSON('not json')")) }
        }
    }
    @Test fun preparedColumnSelectorsAndParserValidationDoNotReorderOrExecuteEditorSql()=scratch { root ->
        connect().use { c ->
            val file=root.resolve("prepared.geojsonl")
            val sql="COPY (SELECT CAST(? AS GEOMETRY) g,CAST(? AS INTEGER) id) TO (?) (FORMAT GEOJSONL,ID_COLUMN ?,GEOMETRY_COLUMN ?)"
            c.prepareStatement(render(sql)).use { p ->
                assertEquals(5,p.parameterMetaData.parameterCount);p.setString(1,"POINT(1 2)");p.setInt(2,42);p.setString(3,file.toString());p.setString(4,"id");p.setString(5,"g");p.execute()
            }
            assertEquals(listOf(listOf("42","POINT (1 2)")),c.rows("SELECT id,ST_AsText(geometry) FROM read_json(${quote(file)})"))
            c.exec("CREATE SEQUENCE audit_seq")
            val absent=root.resolve("never-created.geojson")
            val texts=listOf("COPY (SELECT CAST('POINT(1 2)' AS GEOMETRY) g,nextval('audit_seq') AS id) TO ${quote(absent)} (FORMAT GEOJSON)",
                "SELECT * FROM read_json(CAST(nextval('audit_seq') AS VARCHAR),geojson:=true)")
            val verifier=ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(c.duplicate()) })
            for(text in texts) {
                RelationScope.analyze(text,"duckdb",RelationFacts());SqlFragment(text,"duckdb").toExecutable("duckdb")
                val result=verifier.verify(text);assertTrue(result is VerifyResult.Accepted || result is VerifyResult.Unverified,result.toString())
            }
            assertFalse(Files.exists(absent));assertEquals(listOf(listOf("1")),c.rows("SELECT nextval('audit_seq')"))
        }
    }
}
