package dev.brikk.house.sql.metadata

import kotlin.test.*
class DuckdbRegexAdditionsMetadataTest {
    @Test fun capturedSignaturesDistinguishTheNewOptionsAndNamedStructOverloads() {
        val extract=DUCKDB_METADATA.default.functions["regexp_extract"]!!.overloads
        val all=DUCKDB_METADATA.default.functions["regexp_extract_all"]!!.overloads
        assertEquals(6,extract.size);assertEquals(5,all.size)
        assertTrue(extract.any { it.argTypes==listOf("VARCHAR","VARCHAR","VARCHAR") && it.argNames==listOf("string","regex","options") })
        assertTrue(all.any { it.argTypes==listOf("VARCHAR","VARCHAR","VARCHAR[]") && it.argNames==listOf("string","regex","name_list") })
        assertFalse(all.any { it.argTypes==listOf("VARCHAR","VARCHAR","VARCHAR") })
        assertTrue(extract.all { it.returnType=="VARCHAR" });assertTrue(all.all { it.returnType=="VARCHAR[]" }) // Native registry's nominal types; binding changes named-list result types.
        assertFalse(DUCKDB_METADATA.forServerVersion("1.5.6").snapshot.functions["regexp_extract"]!!.overloads.any { it.argTypes==listOf("VARCHAR","VARCHAR","VARCHAR") })
    }
}
