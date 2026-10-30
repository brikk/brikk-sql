package dev.brikk.house.sql.metadata

import kotlin.test.*

class DuckdbPathFunctionsMetadataTest {
    @Test
    fun capturedPathHelpersHaveExactNativeTwoPointZeroSignaturesWithoutRecapture() {
        val hive = assertNotNull(DUCKDB_METADATA.default.functions["hive_partition_component"])
        assertEquals(FunctionKind.SCALAR,hive.kind)
        assertEquals(listOf("VARCHAR","ANY"),hive.overloads.single().argTypes)
        assertEquals(listOf("name","value"),hive.overloads.single().argNames)
        assertEquals("VARCHAR",hive.overloads.single().returnType)
        assertFalse(hive.overloads.single().variadic)
        val join = assertNotNull(DUCKDB_METADATA.default.functions["path_join"])
        assertEquals(FunctionKind.SCALAR,join.kind)
        assertEquals(listOf("VARCHAR"),join.overloads.single().argTypes)
        assertEquals(listOf("path"),join.overloads.single().argNames)
        assertEquals("VARCHAR",join.overloads.single().returnType)
        assertTrue(join.overloads.single().variadic)
        for (name in listOf("path_join","hive_partition_component"))
            assertNull(DUCKDB_METADATA.forServerVersion("1.5.6").snapshot.functions[name])
    }
}
