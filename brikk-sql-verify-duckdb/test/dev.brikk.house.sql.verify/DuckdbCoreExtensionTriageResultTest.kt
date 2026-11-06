package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.*

/** Grammar-only controls. No statement below is prepared/executed as extension SQL. */
internal class DuckdbCoreExtensionTriageResultTest {
    private fun render(sql: String) = assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql)))).sql("duckdb")
    @Test fun finalDocumentedAttachDdlAndSnapshotSyntaxParsesWithoutInstallingOrContactingProviders() {
        val parent = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode"))
        val root = Files.createTempDirectory(parent, "duckdb-extension-triage-")
        try {
            DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
                setProperty("enable_external_access", "false")
                setProperty("autoload_known_extensions", "false")
                setProperty("autoinstall_known_extensions", "false")
                setProperty("extension_directory", root.resolve("extensions").toString())
            }).use { c ->
                val path = root.resolve("never-created.ducklake").toString().replace("'", "''")
                val attach = listOf(
                    "ATTACH 'ducklake:$path' AS dl (DUCKLAKE_VERSION '1.1',AUTOMATIC_MIGRATION false,METADATA_PARAMETERS MAP {'TYPE':'postgres','SECRET':'not-a-credential'})",
                    "ATTACH 'warehouse' AS ice (TYPE iceberg,URI 'https://catalog.invalid',TABLE_RESOLUTION 'eager',EXTRA_HTTP_HEADERS MAP {'X-Test':'sandbox'},MAX_TABLE_STALENESS INTERVAL '10 minutes')",
                    "ATTACH 'file://$path' AS events (TYPE delta,TIMESTAMP TIMESTAMPTZ '2026-09-01 12:00:00+00')",
                )
                val ddl = listOf(
                    "CREATE TABLE dl.events(id BIGINT,ts TIMESTAMP,payload VARIANT) PARTITIONED BY (epoch_day(ts)) SORTED BY (ts)",
                    "CREATE TABLE events.events(id BIGINT,region VARCHAR,payload VARIANT) PARTITIONED BY(region) WITH('delta.appendOnly'='true')",
                    "CREATE TABLE ice.ns.events(id BIGINT,ts TIMESTAMP) PARTITIONED BY(day(ts),bucket(16,id)) WITH('format-version'=3)",
                    "ALTER TABLE dl.events SET PARTITIONED BY(bucket(8,id))",
                    "ALTER TABLE dl.events RESET PARTITIONED BY",
                    "ALTER TABLE dl.events SET SORTED BY(date_trunc('hour',ts) ASC NULLS LAST,id DESC)",
                    "ALTER TABLE dl.events RESET SORTED BY",
                    "ALTER TABLE dl.events SET(target_file_size='256MB')",
                )
                for (sql in attach+ddl) {
                    val parsed = assertIs<SerializedSql.Failed>(c.serializeSql(sql), sql)
                    assertEquals("not implemented", parsed.type, parsed.message) // Parsed; JSON serializer intentionally supports SELECT only.
                }
                for (sql in attach) assertEquals("not implemented", assertIs<SerializedSql.Failed>(c.serializeSql(render(sql))).type)
                for (sql in ddl) {
                    val preview = SqlFragment(sql, "duckdb").transpileTo("duckdb")
                    assertEquals(sql, preview.sql)
                    assertTrue(preview.unsupportedMessages.isNotEmpty())
                    assertFails { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
                }
                for (sql in listOf("SELECT payload FROM dl.events AT(VERSION => 3)",
                    "SELECT payload FROM events AT(TIMESTAMP => TIMESTAMPTZ '2026-09-01 12:00:00+00')",
                    "SELECT * FROM delta_scan('https://storage.invalid/never-read',timestamp => TIMESTAMPTZ '2026-09-01 12:00:00+00')")) {
                    assertIs<SerializedSql.Parsed>(c.serializeSql(sql), sql)
                    assertIs<SerializedSql.Parsed>(c.serializeSql(render(sql)), sql)
                }
                c.createStatement().use { s -> s.executeQuery("SELECT database_name FROM duckdb_databases()").use { r ->
                    val names = buildSet { while (r.next()) add(r.getString(1)) }
                    assertEquals(setOf("memory", "system", "temp"), names)
                } }
                // duckdb_extensions() enumerates the install filesystem, so use only the
                // in-memory function catalog while external access is disabled.
                c.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM duckdb_functions() WHERE function_name IN ('ducklake_snapshots','iceberg_scan','delta_scan')").use { r ->
                        assertTrue(r.next())
                        assertEquals(0, r.getInt(1))
                    }
                }
                assertTrue(Files.list(root).use { it.findAny().isEmpty })
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
