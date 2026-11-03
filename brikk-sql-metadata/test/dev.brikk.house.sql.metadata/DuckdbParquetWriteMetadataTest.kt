package dev.brikk.house.sql.metadata
import kotlin.test.*
class DuckdbParquetWriteMetadataTest {
    @Test fun writerOptionMetadataIsDistinctFromReadArgumentsAndDoesNotInventStrictStructSignatures() {
        assertEquals("UBIGINT",DUCKDB_PARQUET_WRITE_OPTION_TYPES["DATA_PAGE_SIZE_LIMIT"])
        assertEquals("ANY",DUCKDB_PARQUET_WRITE_OPTION_TYPES["SHREDDING"])
        assertEquals("ANY",DUCKDB_PARQUET_WRITE_OPTION_TYPES["TIMESTAMP_IS_ADJUSTED_TO_UTC"])
        assertEquals("BOOLEAN",DUCKDB_PARQUET_WRITE_OPTION_TYPES["WRITE_TIMESTAMP_AS_INT96"])
        assertFalse("BINARY_AS_STRING" in DUCKDB_PARQUET_WRITE_OPTION_TYPES)
        assertFalse("FILE_ROW_GROUP_NUMBER" in DUCKDB_PARQUET_WRITE_OPTION_TYPES)
        assertEquals(DUCKDB_PARQUET_WRITE_OPTION_TYPES.keys,DUCKDB_PARQUET_WRITE_OPTIONS.map { it.name }.toSet())
        assertFalse("auto" in DUCKDB_PARQUET_WRITE_OPTIONS.single { it.name=="SHREDDING" }.values)
        assertEquals(listOf("AUTO","TRUE","FALSE"),DUCKDB_PARQUET_WRITE_OPTIONS.single { it.name=="TIMESTAMP_IS_ADJUSTED_TO_UTC" }.values)
        assertFalse(DUCKDB_METADATA.default.functions["read_parquet"]!!.overloads.any { "data_page_size_limit" in it.argNames.orEmpty() })
    }
}
