package dev.brikk.house.sql.metadata
import kotlin.test.*
class DuckdbCoreExtensionOptionsTest {
    @Test fun optionVocabularyKeepsCanonicalAndDeprecatedNamesAndBackendBoundariesDistinct() {
        assertEquals(setOf("ducklake","iceberg","delta"),DUCKDB_CORE_EXTENSION_ATTACH_OPTIONS.keys)
        val iceberg=DUCKDB_CORE_EXTENSION_ATTACH_OPTIONS.getValue("iceberg")
        assertNotNull(iceberg.singleOrNull { it.name=="URI" })
        assertTrue(iceberg.single { it.name=="ENDPOINT" }.summary!!.contains("Deprecated"))
        assertEquals(listOf("lazy","eager"),iceberg.single { it.name=="TABLE_RESOLUTION" }.values)
        assertTrue(DUCKDB_CORE_EXTENSION_ATTACH_OPTIONS.getValue("ducklake").any { it.name=="METADATA_PARAMETERS" })
        assertTrue(DUCKDB_CORE_EXTENSION_ATTACH_OPTIONS.getValue("delta").any { it.name=="TIMESTAMP" })
        assertFalse(iceberg.any { it.name=="DUCKLAKE_VERSION" })
    }
}
