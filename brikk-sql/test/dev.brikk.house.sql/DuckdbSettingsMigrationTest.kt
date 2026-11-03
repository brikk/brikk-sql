package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbSettingsMigrationTest {
    @Test fun currentAndLegacySettingSyntaxIsPreservedWithoutAutomaticMigrationOrModeRewriting() {
        for(sql in listOf("SET tracked_metrics=['query.*','optimizer.*']","SET async_threads=0","SET read_ahead_depth=-1",
            "SET profiling_mode='detailed'","SET custom_profiling_settings='{}'","SET enable_http_logging=true","SET http_logging_output='never-opened'",
            "PRAGMA enable_profiling='json'","PRAGMA profiling_output='never-created.json'",
            "CALL enable_logging('HTTP',storage='memory')","CALL enable_profiling(format='no_output')","CALL disable_profiling()",
            "EXPLAIN (FORMAT JSON) SELECT 42")) {
            val tree=parseDuckdbNativeOne(sql)
            assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
            val generated=SqlFragment(sql,"duckdb").toExecutable("duckdb").sql
            assertEquals(generated,parseDuckdbNativeOne(generated).sql("duckdb"))
            if("custom_profiling_settings" in sql)assertTrue("custom_profiling_settings" in generated)
            if("profiling_mode" in sql)assertTrue("detailed" in generated)
        }
    }
    @Test fun metricsArrayParametersKeepAuthoredOrderAndRendererOptionsStayNative() {
        val sql="SET tracked_metrics=[?,?]"
        val out=SqlFragment(sql,"duckdb").toExecutable("duckdb")
        assertEquals(2,out.sql.count { it=='?' });assertEquals(out.sql,assertNotNull(out.sourceMap).output)
        assertTrue("FORMAT JSON" in SqlFragment("EXPLAIN (FORMAT JSON) SELECT 42","duckdb").toExecutable("duckdb").sql)
    }
}
