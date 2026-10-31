package dev.brikk.house.sql.metadata

/** DuckDB 2.0 JSON-extension GeoJSON COPY TO options, not read_json parameters. */
val DUCKDB_GEOJSON_COPY_WRITE_OPTIONS: List<PropertyDef> = listOf(
    PropertyDef("ARRAY",listOf("true","false"),"true writes a FeatureCollection; false writes newline-delimited Features. GEOJSON defaults true and GEOJSONL defaults false.","2.0"),
    PropertyDef("ID_COLUMN",summary="Select the Feature id column; NULL disables automatic id selection.",since="2.0"),
    PropertyDef("GEOMETRY_COLUMN",summary="Select the GEOMETRY column; NULL writes unlocated Features with null geometry.",since="2.0"),
    PropertyDef("BBOX",listOf("true","false"),"Write the finite geometry's bounding box. NULL/empty geometries need no bbox.","2.0"),
)
