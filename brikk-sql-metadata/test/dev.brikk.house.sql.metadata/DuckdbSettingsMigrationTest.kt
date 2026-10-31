package dev.brikk.house.sql.metadata

import kotlin.test.*

class DuckdbSettingsMigrationTest {
    @Test fun removedSettingsStayHistoricalWhileNewMetricAndIoMetadataMatchTwoPointZero() {
        val old=DUCKDB_DUMPS.single { it.engineVersion=="1.5.6" }.settings!!.associateBy { it.name }
        val current=DUCKDB_DUMPS.single { it.engineVersion=="2.0.0" }.settings!!.associateBy { it.name }
        for(name in listOf("custom_profiling_settings","enable_http_logging","http_logging_output","profiling_mode")) {
            assertTrue(name in old,name);assertFalse(name in current,name)
        }
        for((name,type,scope)in listOf(Triple("tracked_metrics","VARCHAR[]","LOCAL"),Triple("async_threads","BIGINT","GLOBAL"),Triple("read_ahead_depth","BIGINT","GLOBAL"))) {
            assertEquals(type,current.getValue(name).inputType);assertEquals(scope,current.getValue(name).scope)
        }
        assertEquals(FunctionKind.TABLE_VALUED,DUCKDB_METADATA.default.functions["enable_logging"]!!.kind)
        assertEquals(FunctionKind.TABLE_VALUED,DUCKDB_METADATA.default.functions["enable_profiling"]!!.kind)
    }
}
