package dev.brikk.house.sql.metadata

import kotlin.test.*
class DuckdbSetvalMetadataTest {
    @Test fun twoNativeOverloadsAreExactAndOlderSnapshotDoesNotInventThem() {
        val function=assertNotNull(DUCKDB_METADATA.default.functions["setval"])
        assertEquals(FunctionKind.SCALAR,function.kind)
        assertEquals(setOf(listOf("VARCHAR","BIGINT"),listOf("VARCHAR","BIGINT","BOOLEAN")),function.overloads.map { it.argTypes }.toSet())
        assertEquals(setOf(listOf("sequence_name","value"),listOf("sequence_name","value","is_called")),function.overloads.map { it.argNames }.toSet())
        assertTrue(function.overloads.all { it.returnType=="BIGINT" && !it.variadic })
        assertNull(DUCKDB_METADATA.forServerVersion("1.5.6").snapshot.functions["setval"])
    }
}
