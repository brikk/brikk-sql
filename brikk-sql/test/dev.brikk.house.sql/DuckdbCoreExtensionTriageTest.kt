package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.duckdbStorageDdlIssue
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbCoreExtensionTriageTest {
    private val forms = listOf(
        "ATTACH 'ducklake:never-created.ducklake' AS dl (DATA_PATH 'never-created-files/',DUCKLAKE_VERSION '1.1',AUTOMATIC_MIGRATION false,METADATA_PARAMETERS MAP {'TYPE':'postgres','SECRET':'test_secret'})",
        "ATTACH 'warehouse' AS ice (TYPE iceberg,URI 'https://catalog.invalid',AUTHORIZATION_TYPE 'NONE',TABLE_RESOLUTION 'eager',SUPPORT_NESTED_NAMESPACES true,EXTRA_HTTP_HEADERS MAP {'X-Test':'sandbox'},MAX_TABLE_STALENESS INTERVAL '10 minutes')",
        "ATTACH 'file:///never-created-delta' AS events (TYPE delta,VERSION 5)",
        "ATTACH 'file:///never-created-delta' AS events (TYPE delta,TIMESTAMP TIMESTAMPTZ '2026-09-01 12:00:00+00')",
        "CREATE TABLE dl.events(id BIGINT,ts TIMESTAMP,payload VARIANT) PARTITIONED BY (epoch_day(ts)) SORTED BY (ts)",
        "CREATE TABLE events.events(id BIGINT,region VARCHAR,payload VARIANT) PARTITIONED BY (region) WITH ('delta.appendOnly'='true','location'='file:///never-created-delta')",
        "CREATE TABLE ice.ns.events(id BIGINT,ts TIMESTAMP,payload VARIANT) PARTITIONED BY (day(ts),bucket(16,id)) WITH ('format-version'=3)",
        "ALTER TABLE dl.events SET PARTITIONED BY (bucket(8,id),epoch_day(ts))",
        "ALTER TABLE dl.events RESET PARTITIONED BY",
        "ALTER TABLE dl.events SET SORTED BY (date_trunc('hour',ts) ASC NULLS LAST,id DESC)",
        "ALTER TABLE dl.events RESET SORTED BY",
        "ALTER TABLE dl.events SET (target_file_size='256MB')",
        "SELECT payload FROM dl.events AT (VERSION => 3)",
        "SELECT payload FROM events AT (TIMESTAMP => TIMESTAMPTZ '2026-09-01 12:00:00+00')",
        "SELECT * FROM delta_scan('file:///never-read',timestamp => TIMESTAMPTZ '2026-09-01 12:00:00+00')",
    )
    @Test fun documentedFormsAreEitherPreservedOrExplicitlyDiagnosticOnlyWithoutLossyExecution() {
        for (sql in forms) {
            if (duckdbStorageDdlIssue(sql) != null) {
                val fragment = SqlFragment(sql, "duckdb")
                val preview = fragment.transpileTo("duckdb")
                assertEquals(sql, preview.sql)
                assertTrue(preview.isRawPassthroughStatement)
                assertTrue(preview.unsupportedMessages.any { "lossless" in it })
                assertFailsWith<UnsupportedError> { fragment.toExecutable("duckdb") }
                assertFails {
                    fragment.sourceTables
                }
                assertFails {
                    fragment.outputShape()
                }
                assertFailsWith<UnsupportedError> { parseParameterOccurrences(sql, "duckdb") }
            } else {
                val tree = Dialects.DUCKDB.parseOne(sql)
                assertEquals(tree, Serde.load(Serde.dump(tree)))
                val generated = tree.sql("duckdb")
                assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"))
                assertIs<Expression>(tree)
                assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
            }
        }
    }
    @Test fun snapshotStateDoesNotBorrowCurrentTableFactsOrInventLineage() {
        val facts = RelationFacts(tables = mapOf("dl.events" to RelationFact(listOf(ColumnFact("payload", "VARCHAR")))))
        for (sql in listOf("SELECT payload FROM dl.events AT (VERSION => 3)", "SELECT * FROM delta_scan('never-read',version=>5)")) {
            val fragment = SqlFragment(sql, "duckdb")
            assertFailsWith<UnsupportedError> { RelationScope.analyze(sql, "duckdb", facts) }
            assertFails {
                fragment.outputShape()
            }
            assertFails {
                fragment.sourceTables
            }
            assertFails {
                fragment.columnDependencies()
            }
            assertFails { qualify(fragment.ast.copy(), dialect = Dialects.DUCKDB) }
            assertFailsWith<UnsupportedError> { fragment.toExecutable("postgres") }
        }
        val sql = "SELECT payload FROM dl.events AT (VERSION => ?)"
        assertEquals(listOf(sql.indexOf('?')), parseParameterOccurrences(sql, "duckdb").map { it.range.start })
    }
    @Test fun extensionWordsRemainOrdinaryIdentifiersAndDdlInsideStringsDoesNotTriggerTheGate() {
        for (sql in listOf("SELECT bucket,epoch_day,truncate,sorted FROM t", "SELECT 'CREATE TABLE t WITH (x=1)' AS example")) {
            assertNull(duckdbStorageDdlIssue(sql))
            assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
        }
    }
}
