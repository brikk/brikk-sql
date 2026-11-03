package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.redactedClickhouseSql
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class Clickhouse2026DdlTest {
    private fun roundTrip(sql: kotlin.String): Expression {
        val ast = parseOne(sql, "clickhouse")
        assertFalse(ast is Command, sql)
        assertEquals(ast, parseOne(ast.sql("clickhouse"), "clickhouse"), sql)
        assertEquals(ast, Serde.load(Serde.dump(ast)), sql)
        return ast
    }

    @Test
    fun incrementalRefreshKeepsScheduleDependenciesSettingsAndTarget() {
        val ast = roundTrip("CREATE MATERIALIZED VIEW v REFRESH EVERY 1 DAY OFFSET 1 HOUR RANDOMIZE FOR 1 MINUTE DEPENDS ON db.a, db.b SETTINGS refresh_retries = 2 APPEND INCREMENTAL TO db.dst AS SELECT * FROM db.src")
        assertNotNull(ast.find<ClickhouseRefresh>())
        assertNotNull(ast.find<AutoRefreshProperty>())
        assertTrue("TO db.dst" in ast.sql("clickhouse"))
    }

    @Test
    fun hypotheticalIndexesAndExplainsAreStructuredWithExecutionClassification() {
        assertIs<ClickhouseHypotheticalIndex>(roundTrip("CREATE HYPOTHETICAL INDEX i ON t (x) TYPE minmax GRANULARITY 1"))
        for (kind in listOf("ANALYZE", "WHATIF")) {
            val sql = "EXPLAIN $kind SELECT 1"
            val ast = assertIs<ClickhouseExplain>(roundTrip(sql))
            assertEquals(kind == "ANALYZE", ast.executesQuery)
            assertEquals(if (kind == "ANALYZE") StatementEffect.EXECUTES_QUERY else StatementEffect.PLANS_OR_INSPECTS,
                SqlFragment(sql, "clickhouse").statementEffect)
        }
        roundTrip("EXPLAIN ANALYZE processors = 1, time = 0 SELECT * FROM url('https://invalid.test', 'TSV', 'x UInt32')")
        assertIs<Command>(parseOne("EXPLAIN PIPELINE SELECT 1", "clickhouse"))
        assertIs<Command>(parseOne("SYSTEM FLUSH LOGS", "clickhouse"))
        assertIs<Command>(parseOne("CREATE USER u IDENTIFIED BY 'GRANTS' HOST ANY", "clickhouse"))
    }

    @Test
    fun tokensAndAuthenticationKeepGrantsAndOfferExplicitCredentialRedaction() {
        assertIs<ClickhouseCreateToken>(roundTrip("CREATE TOKEN GRANTS (SELECT, INSERT ON db.t, SELECT ON db.u) VALID UNTIL 'infinity' FORMAT TSVRaw"))
        val sql = "CREATE USER u IDENTIFIED WITH sha256_password BY 'fake-credential' VALID FOR INTERVAL 1 DAY GRANTS (SELECT ON db.t)"
        val ast = roundTrip(sql)
        assertNotNull(ast.find<ClickhouseCredential>())
        val redacted = redactedClickhouseSql(ast)
        assertFalse("fake-credential" in redacted)
        assertTrue("[REDACTED]" in redacted)
        assertTrue("GRANTS" in redacted)
        assertTrue("fake-credential" in ast.sql("clickhouse"))
        assertFalse("fake-credential" in ast.find<ClickhouseCredential>().toString())
        assertFalse("fake-credential" in ast.toString())
        assertEquals(StatementEffect.MODIFIES_STATE, SqlFragment("CREATE TOKEN", "clickhouse").statementEffect)
    }

    @Test
    fun handlersKeepHttpClausesAndNestedStatementsWithoutExecutingThem() {
        val ast = roundTrip("CREATE HANDLER IF NOT EXISTS h ON CLUSTER 'cluster' PROTOCOL ANY URL PREFIX '/api' METHODS (GET, POST) TYPE query AS SELECT {id:UInt64}")
        assertIs<ClickhouseHandler>(ast)
        assertEquals(1, SqlFragment(ast.sql("clickhouse"), "clickhouse").scalarParams.count())
        val text = ast.sql("clickhouse")
        assertEquals(text, SqlFragment(text, "clickhouse").toSourcePreservingExecutable().sql)
        assertIs<ClickhouseHandler>(roundTrip("ALTER HANDLER h URL '/x' METHODS (POST) AS INSERT INTO db.t SELECT 1"))
        assertIs<ClickhouseHandler>(roundTrip("DROP HANDLER IF EXISTS h"))
    }

    @Test
    fun enumConstraintProjectionAndSystemActionsKeepEveryClause() {
        val enum = roundTrip("ALTER TABLE t MODIFY COLUMN status ADD ENUM VALUES ('new' = 3, 'auto', 'negative' = -1)")
        assertNotNull(enum.find<ClickhouseAlterAction>())
        roundTrip("ALTER TABLE t MODIFY CONSTRAINT IF EXISTS positive ASSUME x > 0")
        roundTrip("CREATE TABLE t (x UInt32, PROJECTION p INDEX x TYPE minmax) ENGINE = MergeTree ORDER BY x")
        roundTrip("ALTER TABLE t MODIFY PROJECTION IF EXISTS p (SELECT x ORDER BY x) WITH SETTINGS (deduplicate_merge_projection_mode = 'drop')")
        roundTrip("ALTER TABLE t MODIFY PROJECTION p INDEX x TYPE minmax WITH SETTINGS (deduplicate_merge_projection_mode = 'drop')")
        roundTrip("ALTER TABLE t ADD PROJECTION IF NOT EXISTS p INDEX x TYPE minmax WITH SETTINGS (deduplicate_merge_projection_mode = 'drop') FIRST")
        roundTrip("ALTER TABLE t DROP PROJECTION IF EXISTS p")
        roundTrip("ALTER TABLE t MATERIALIZE PROJECTION p IN PARTITION 1")
        roundTrip("ALTER TABLE t MODIFY CONSTRAINT positive CHECK x > 0, MODIFY COLUMN status ADD ENUM VALUES ('new' = 3)")
        for (verb in listOf("START", "STOP", "PAUSE", "CANCEL", "REFRESH")) {
            assertIs<ClickhouseSystem>(roundTrip("SYSTEM $verb db.t"))
            assertIs<ClickhouseSystem>(roundTrip("SYSTEM $verb ALL BACKGROUND"))
        }
    }

    @Test
    fun malformedNativeFormsAreNotSuccessfulPartialNodes() {
        for (sql in listOf("CREATE HANDLER h AS SELECT 1", "CREATE HANDLER h URL '/x' METHODS (PATCH)",
            "CREATE TOKEN GRANTS ()", "CREATE TOKEN VALID FOR INTERVAL 1 DAY VALID UNTIL 'infinity'",
            "CREATE HYPOTHETICAL INDEX i ON t () TYPE minmax", "ALTER TABLE t MODIFY COLUMN c ADD ENUM VALUES ()")) {
            assertFailsWith<ParseError>(sql) { parseOne(sql, "clickhouse") }
        }
    }

    @Test
    fun newStatementsRefuseCrossDialectExecution() {
        for (sql in listOf("CREATE TOKEN", "CREATE HANDLER h URL '/x' AS SELECT 1", "EXPLAIN ANALYZE SELECT 1",
            "CREATE HYPOTHETICAL INDEX i ON t (x) TYPE minmax", "SYSTEM PAUSE db.t")) {
            for (target in listOf("duckdb", "trino", "doris")) assertFailsWith<UnsupportedError>(sql) {
                SqlFragment(sql, "clickhouse").toExecutable(target)
            }
        }
    }
}
