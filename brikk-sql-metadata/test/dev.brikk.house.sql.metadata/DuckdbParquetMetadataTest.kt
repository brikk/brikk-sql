package dev.brikk.house.sql.metadata
import kotlin.test.*
class DuckdbParquetMetadataTest {
    @Test fun schemaIsANamedAnyParameterButRowGroupNumberIsNotAParameter() {
        for(name in listOf("read_parquet","parquet_scan")) {
            val function=DUCKDB_METADATA.default.functions.getAll(name).single { it.kind==FunctionKind.TABLE_VALUED }
            assertTrue(function.overloads.isNotEmpty())
            for(overload in function.overloads) {
                val names=overload.argNames!!
                assertEquals("ANY",overload.argTypes[names.indexOf("schema")])
                assertFalse("file_row_group_number" in names)
                assertEquals(1,overload.positionalCount)
            }
        }
    }
}
