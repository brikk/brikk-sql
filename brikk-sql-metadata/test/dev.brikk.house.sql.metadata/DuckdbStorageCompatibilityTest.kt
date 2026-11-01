package dev.brikk.house.sql.metadata
import kotlin.test.*

class DuckdbStorageCompatibilityTest {
    @Test fun compatibilitySettingRemainsVersionedMetadataNotAnInferredUniversalFileVersion() {
        for(version in listOf("1.5.6","2.0.0")) {
            val setting=DUCKDB_DUMPS.single { it.engineVersion==version }.settings!!.single { it.name=="storage_compatibility_version" }
            assertEquals("VARCHAR",setting.inputType);assertEquals("GLOBAL",setting.scope)
        }
    }
}
