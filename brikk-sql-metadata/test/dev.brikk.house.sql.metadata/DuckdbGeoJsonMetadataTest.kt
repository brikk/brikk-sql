package dev.brikk.house.sql.metadata
import kotlin.test.*
class DuckdbGeoJsonMetadataTest {
    @Test fun geojsonReadFlagConvertersAndWriterOptionsStayInTheirOwnNamespaces() {
        for(name in listOf("read_json","read_json_auto","read_ndjson","read_ndjson_auto"))
            for(overload in DUCKDB_METADATA.default.functions[name]!!.overloads) {
                assertEquals("BOOLEAN",overload.argTypes[overload.argNames!!.indexOf("geojson")])
                assertFalse("id_column" in overload.argNames!!);assertFalse("geometry_column" in overload.argNames!!)
            }
        assertEquals("JSON",DUCKDB_METADATA.default.functions["st_asgeojson"]!!.overloads.single().returnType)
        assertEquals(setOf(listOf("JSON"),listOf("VARCHAR")),DUCKDB_METADATA.default.functions["st_geomfromgeojson"]!!.overloads.map { it.argTypes }.toSet())
        assertTrue(DUCKDB_METADATA.default.functions["st_geomfromgeojson"]!!.overloads.all { it.returnType=="GEOMETRY" })
        assertEquals(setOf("ARRAY","ID_COLUMN","GEOMETRY_COLUMN","BBOX"),DUCKDB_GEOJSON_COPY_WRITE_OPTIONS.map { it.name }.toSet())
    }
}
