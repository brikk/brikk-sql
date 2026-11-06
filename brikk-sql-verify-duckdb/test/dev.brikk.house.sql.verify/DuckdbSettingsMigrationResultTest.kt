package dev.brikk.house.sql.verify

import dev.brikk.house.sql.metadata.*
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

internal class DuckdbSettingsMigrationResultTest {
    private fun connection() = DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
        setProperty("enable_external_access", "false")
        setProperty("autoload_known_extensions", "false")
        setProperty("autoinstall_known_extensions", "false")
    })
    private fun Connection.rows(sql: String) = createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun Connection.run(sql: String, generated: Boolean) = createStatement().use { it.execute(if (generated) SqlFragment(sql, "duckdb").toExecutable("duckdb").sql else sql) }
    @Test fun removedSettingsErrorAndLiveMetadataMatchesTheVersionedReplacementRows() {
        connection().use { c ->
            val live = c.createStatement().use { s -> s.executeQuery(DUCKDB_SETTINGS.sql).use { r -> buildList {
                while (r.next()) add(DUCKDB_SETTINGS.decode(DUCKDB_SETTINGS.columns.indices.map { r.getString(it + 1) }))
            }.associateBy { it.name } } }
            for (name in listOf("custom_profiling_settings", "enable_http_logging", "http_logging_output", "profiling_mode")) assertFalse(name in live)
            val captured = DUCKDB_DUMPS.single { it.engineVersion == "2.0.0" }.settings!!.associateBy { it.name }
            for (name in listOf("tracked_metrics", "async_threads", "read_ahead_depth")) assertEquals(captured[name], live[name])
            for (generated in listOf(false, true)) for (sql in listOf("SET custom_profiling_settings='{}'", "SET enable_http_logging=true", "SET http_logging_output='never-created.log'"))
                assertTrue(assertFailsWith<SQLException> { c.run(sql, generated) }.message!!.contains("unrecognized configuration parameter"))
        }
    }
    @Test fun profilingModeChoicesAreEquivalentButStillEnableProfilingAndValidateTheirNames() {
        for (generated in listOf(false, true)) connection().use { c ->
            c.run("CALL enable_profiling(format='no_output')", generated)
            c.run("CALL disable_profiling()", generated)
            c.run("SET tracked_metrics=['query.*']", generated)
            for (mode in listOf("standard", "detailed", "all")) {
                c.run("SET profiling_mode='$mode'", generated)
                assertEquals(listOf(listOf("no_output", "standard", "[\"query.*\"]")), c.rows("SELECT current_setting('enable_profiling'),current_setting('profiling_mode'),to_json(current_setting('tracked_metrics'))"))
                c.run("CALL disable_profiling()", generated)
                assertEquals(listOf(listOf<String?>(null)), c.rows("SELECT current_setting('enable_profiling')"))
            }
            assertFailsWith<SQLException> { c.run("SET profiling_mode='unknown'", generated) }
            c.run("CALL enable_logging('HTTP',storage='memory')", generated)
            // BOOLEAN input metadata is not a promise about the live getter's type.
            assertEquals(listOf(listOf("INTEGER", "1", "true")), c.rows("SELECT typeof(current_setting('enable_logging')),current_setting('enable_logging'),CAST(current_setting('enable_logging') AS BOOLEAN)"))
            c.run("CALL disable_logging()", generated)
        }
    }
    @Test fun asyncReadAheadBoundariesAndExplainRenderersAreNativeWithoutPerformingProviderIo() {
        for (generated in listOf(false, true)) connection().use { c ->
            for (value in listOf(0, 1)) {
                c.run("SET async_threads=$value", generated)
                assertEquals(listOf(listOf(value.toString())), c.rows("SELECT current_setting('async_threads')"))
            }
            for (value in listOf(-1, 0, 2)) {
                c.run("SET read_ahead_depth=$value", generated)
                assertEquals(listOf(listOf(value.toString())), c.rows("SELECT current_setting('read_ahead_depth')"))
            }
            for (sql in listOf("SET async_threads=NULL", "SET read_ahead_depth=-2", "SET tracked_metrics=NULL")) assertFailsWith<SQLException> { c.run(sql, generated) }
            val sql = if (generated) SqlFragment("EXPLAIN (FORMAT JSON) SELECT 42", "duckdb").toExecutable("duckdb").sql else "EXPLAIN (FORMAT JSON) SELECT 42"
            val plan = c.rows(sql).single()
            assertEquals("physical_plan", plan[0])
            assertTrue("PROJECTION" in plan[1].orEmpty())
            assertTrue("42" in plan[1].orEmpty())
        }
    }
}
