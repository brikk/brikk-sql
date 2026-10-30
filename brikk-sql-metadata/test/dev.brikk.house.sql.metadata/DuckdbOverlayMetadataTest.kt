package dev.brikk.house.sql.metadata

import kotlin.test.*

class DuckdbOverlayMetadataTest {
    @Test
    fun nativeTwoPointZeroOverlaySignaturesAreExactAndAbsentFromTheOlderCapture() {
        val function = assertNotNull(DUCKDB_METADATA.default.functions["overlay"])
        assertEquals(FunctionKind.SCALAR, function.kind)
        assertEquals(setOf(listOf("VARCHAR", "VARCHAR", "BIGINT"), listOf("VARCHAR", "VARCHAR", "BIGINT", "BIGINT")), function.overloads.map { it.argTypes }.toSet())
        assertTrue(function.overloads.all { it.returnType == "VARCHAR" && !it.variadic })
        assertEquals(setOf(listOf("string", "replacement", "start"), listOf("string", "replacement", "start", "count")), function.overloads.map { it.argNames }.toSet())
        assertNull(DUCKDB_METADATA.forServerVersion("1.5.6").snapshot.functions["overlay"])
    }
}
