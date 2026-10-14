package dev.brikk.house.sql.intellij.catalog

import junit.framework.TestCase

class StatementHeadDetectorTest : TestCase() {
    private val duckdb = StatementHeadDetector(StatementRules.DUCKDB, "duckdb")
    private val trino = StatementHeadDetector(StatementRules.TRINO, "trino")
    private val doris = StatementHeadDetector(StatementRules.DORIS, "doris")
    private val clickhouse = StatementHeadDetector(StatementRules.CLICKHOUSE, "clickhouse")

    private fun StatementHeadDetector.heads(script: String) = detect(script).map { it.rule.pattern }

    fun testExtensionStatementsChangeTheFunctionInventory() {
        assertEquals(listOf("INSTALL", "LOAD", "FORCE INSTALL"), duckdb.heads("install httpfs; LOAD httpfs;\n  FORCE  INSTALL spatial"))
        assertEquals(StatementEffect.FunctionInventory(sessionFacts = true), duckdb.detect("load spatial").single().effect)
        assertEquals(StatementEffect.FunctionInventory(sessionFacts = false), duckdb.detect("install spatial").single().effect)
    }

    fun testOnlyWholeLeadingWordsMatch() {
        assertEquals(emptyList<String>(), duckdb.heads("SELECT * FROM install_log; INSTALLER x; install2 y; FORCE CHECKPOINT; UPDATE EXTENSIONS; \"LOAD\" x"))
        assertEquals(emptyList<String>(), duckdb.heads("SELECT 1 /* ; LOAD x */"))
    }

    fun testQuotesAndCommentsNeitherSplitNorStartStatements() {
        assertEquals(emptyList<String>(), duckdb.heads("SELECT 'a; LOAD x'"))
        assertEquals(emptyList<String>(), duckdb.heads("SELECT \"a;LOAD\" FROM t"))
        assertEquals(emptyList<String>(), duckdb.heads("SELECT $$ ; LOAD x $$"))
        assertEquals(emptyList<String>(), duckdb.heads("-- LOAD x\nSELECT 1"))
        assertEquals(listOf("LOAD"), duckdb.heads("-- comment\n/* block */ LOAD x"))
        assertEquals(listOf("LOAD"), duckdb.heads("SELECT 'it''s'; LOAD x"))
    }

    fun testOptionalWordsMayBeLeftOut() {
        val temporary = "CREATE [OR REPLACE] TEMP|TEMPORARY MACRO|FUNCTION"
        for (sql in listOf("create or replace temp macro m(x) AS x", "CREATE TEMPORARY FUNCTION f() AS 1")) {
            assertEquals(sql, listOf(temporary), duckdb.heads(sql))
        }
        assertEquals(listOf("CREATE [OR REPLACE] MACRO|FUNCTION"), duckdb.heads("CREATE MACRO m(x) AS x"))
        assertEquals(emptyList<String>(), duckdb.heads("CREATE OR MACRO m() AS 1"))
    }

    fun testNestedCommentsHideNothingAfterThem() {
        assertEquals(listOf("CREATE [OR REPLACE] MACRO|FUNCTION"), duckdb.heads("/* outer /* inner */ still a comment */ CREATE MACRO m() AS 1"))
    }

    fun testEscapeStringsNeitherStartNorHideStatements() {
        assertEquals(listOf("CREATE [OR REPLACE] TEMP|TEMPORARY MACRO|FUNCTION"), duckdb.heads("SELECT E'it\\'s; LOAD x'; CREATE TEMP MACRO m() AS 1"))
    }

    fun testRulesMayAskToNotify() {
        val rule = StatementRules.DUCKDB.first { it.pattern == "LOAD" }
        assertFalse(rule.notifies)
        assertTrue(StatementHeadDetector(listOf(rule.notifying()), "duckdb").detect("LOAD x").single().rule.notifies)
    }

    fun testTheNameAfterTheHeadIsRead() {
        assertEquals(listOf("hive", "web"), trino.detect("CREATE SCHEMA IF NOT EXISTS hive.web").single().name)
        assertEquals(listOf("my\"cat", "Web"), trino.detect("drop schema \"my\"\"cat\". \"Web\" cascade").single().name)
        assertEquals(listOf("lake"), doris.detect("REFRESH CATALOG `lake`").single().name)
        assertEquals(listOf("x"), trino.detect("DROP CATALOG IF EXISTS x").single().name)
        assertEquals("a string literal names nothing", emptyList<String>(), duckdb.detect("ATTACH DATABASE 'file.db' AS f").single().name)
    }

    fun testNamespaceEffects() {
        assertEquals(StatementEffect.Namespace(1, 0), duckdb.detect("DETACH f").single().effect)
        assertEquals(StatementEffect.Namespace(2, 1), trino.detect("CREATE SCHEMA a.b").single().effect)
        assertEquals(StatementEffect.Namespace(1, 1), doris.detect("REFRESH CATALOG c").single().effect)
        assertEquals(StatementEffect.Namespace(1, 0), doris.detect("CREATE CATALOG c PROPERTIES ('type'='hms')").single().effect)
    }

    fun testTableViewAndSchemaDdlReloadTheNamespaceHoldingWhatItChanges() {
        fun effects(detector: StatementHeadDetector, script: String) = detector.detect(script).map { it.effect to it.name }
        val table = StatementEffect.Namespace(3)
        for (sql in listOf(
            "CREATE TABLE live(x INT)", "create or replace temp table live AS SELECT 1", "CREATE TABLE IF NOT EXISTS live(x INT)",
            "ALTER TABLE live ADD COLUMN y INT", "DROP TABLE IF EXISTS live", "CREATE OR REPLACE VIEW live AS SELECT 1",
            "ALTER VIEW live RENAME TO other", "DROP VIEW live",
        )) assertEquals(sql, listOf(table to listOf("live")), effects(duckdb, sql))
        assertEquals(listOf(table to listOf("lake", "main", "t")), effects(duckdb, "CREATE TABLE lake.main.t(x INT)"))
        assertEquals(listOf(StatementEffect.Namespace(2) to listOf("web")), effects(duckdb, "CREATE SCHEMA IF NOT EXISTS web"))
        assertEquals(listOf(StatementEffect.Namespace(2) to listOf("lake", "web")), effects(duckdb, "DROP SCHEMA lake.web CASCADE"))
        // The function inventory rules still come first.
        assertEquals(StatementEffect.FunctionInventory(sessionFacts = true), duckdb.detect("CREATE TEMP MACRO m() AS 1").single().effect)

        for (sql in listOf(
            "CREATE TABLE hive.web.t (x int)", "CREATE OR REPLACE TABLE hive.web.t AS SELECT 1", "CREATE MATERIALIZED VIEW hive.web.t AS SELECT 1",
            "ALTER TABLE hive.web.t ADD COLUMN y int", "ALTER MATERIALIZED VIEW hive.web.t RENAME TO u", "DROP VIEW IF EXISTS hive.web.t",
            "DROP MATERIALIZED VIEW hive.web.t",
        )) assertEquals(sql, listOf(table to listOf("hive", "web", "t")), effects(trino, sql))
        assertEquals(listOf(StatementEffect.Namespace(2) to listOf("hive", "web")), effects(trino, "ALTER SCHEMA hive.web RENAME TO www"))

        for (sql in listOf(
            "CREATE TABLE t (k INT) DISTRIBUTED BY HASH(k) BUCKETS 1", "CREATE EXTERNAL TABLE t (k INT) ENGINE=hive", "CREATE TEMPORARY TABLE t (k INT)",
            "CREATE TABLE IF NOT EXISTS t LIKE s", "CREATE OR REPLACE VIEW t AS SELECT 1", "CREATE MATERIALIZED VIEW t AS SELECT 1",
            "ALTER TABLE t ADD COLUMN v INT", "ALTER VIEW t AS SELECT 2", "DROP TABLE IF EXISTS t FORCE", "DROP VIEW t", "DROP MATERIALIZED VIEW t",
        )) assertEquals(sql, listOf(table to listOf("t")), effects(doris, sql))
        for (sql in listOf("CREATE DATABASE IF NOT EXISTS app", "CREATE SCHEMA app", "DROP DATABASE app FORCE", "ALTER DATABASE app RENAME app2")) {
            assertEquals(sql, listOf(StatementEffect.Namespace(2) to listOf("app")), effects(doris, sql))
        }

        val chTable = StatementEffect.Namespace(2)
        for (sql in listOf(
            "CREATE TABLE db.t (x UInt8) ENGINE = Memory", "CREATE OR REPLACE TABLE db.t ENGINE = Memory AS SELECT 1", "CREATE TEMPORARY TABLE db.t (x UInt8)",
            "CREATE VIEW db.t AS SELECT 1", "CREATE MATERIALIZED VIEW IF NOT EXISTS db.t TO db.s AS SELECT 1", "CREATE DICTIONARY db.t (k UInt64) PRIMARY KEY k",
            "ALTER TABLE db.t ON CLUSTER c ADD COLUMN y UInt8", "DROP TABLE IF EXISTS db.t SYNC", "DROP DICTIONARY db.t", "RENAME TABLE db.t TO db.u",
            "EXCHANGE TABLES db.t AND db.u", "ATTACH TABLE db.t", "DETACH VIEW db.t",
        )) assertEquals(sql, listOf(chTable to listOf("db", "t")), effects(clickhouse, sql))
        for (sql in listOf("CREATE DATABASE IF NOT EXISTS db", "DROP DATABASE db", "ATTACH DATABASE db", "DETACH DATABASE db", "RENAME DATABASE db TO db2")) {
            assertEquals(sql, listOf(StatementEffect.Namespace(1) to listOf("db")), effects(clickhouse, sql))
        }
        assertEquals(emptyList<Any>(), effects(clickhouse, "SELECT 1; INSERT INTO db.t VALUES (1); OPTIMIZE TABLE db.t; TRUNCATE TABLE db.t"))
    }

    fun testMalformedPatternsAreRejected() {
        for (pattern in listOf("[CREATE", "CREATE]", "[OPTIONAL]", "")) {
            assertTrue(pattern, runCatching { StatementRule(pattern, StatementEffect.FunctionInventory()) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    fun testTransactionControl() {
        val transactions = StatementHeadDetector(StatementRules.TRANSACTIONS, "duckdb")
        fun begins(sql: String) = transactions.detect(sql).map { (it.effect as StatementEffect.Transaction).begins }
        assertEquals(listOf(true, true, true), begins("BEGIN; begin transaction; START TRANSACTION"))
        assertEquals(listOf(false, false, false, false, false), begins("COMMIT; ROLLBACK WORK; abort; END; END TRANSACTION"))
        assertEquals("a savepoint rollback keeps the transaction", listOf(null), begins("ROLLBACK TO SAVEPOINT s"))
        assertEquals("a chained end starts the next transaction", listOf(true, true, true), begins("COMMIT AND CHAIN; rollback work and chain; END AND CHAIN"))
        assertEquals(listOf(false), begins("COMMIT AND NO CHAIN"))
        assertEquals("two-phase: preparing ends the session's transaction", listOf(false), begins("PREPARE TRANSACTION 'gid'"))
        assertEquals("finishing a prepared one leaves the session's as it is", listOf(null, null), begins("COMMIT PREPARED 'gid'; ROLLBACK PREPARED 'gid'"))
    }

    /** Only the two-phase forms, a literal transaction id and nothing after it, are transaction control. */
    fun testPreparedStatementsNamedTransactionAreNotTwoPhase() {
        for (dialect in listOf("duckdb", "postgres")) {
            val transactions = StatementHeadDetector(StatementRules.TRANSACTIONS, dialect)
            fun begins(sql: String) = transactions.detect(sql).map { (it.effect as StatementEffect.Transaction).begins }
            assertEquals(dialect, listOf(false, null, null), begins("prepare transaction 'gid'; COMMIT PREPARED 'g''id'; rollback prepared 'gid' -- done"))
            for (sql in listOf(
                "PREPARE TRANSACTION AS SELECT 1",
                "PREPARE TRANSACTION FROM 'SELECT 1'",
                "PREPARE TRANSACTION (INT) AS SELECT \$1",
                "PREPARE TRANSACTION 'gid' AS SELECT 1",
                "PREPARE TRANSACTION",
            )) assertEquals("$dialect: $sql", emptyList<Boolean?>(), begins(sql))
            // Not the two-phase form either, but never an end: these must not fall through to COMMIT and ROLLBACK.
            for (sql in listOf("COMMIT PREPARED", "COMMIT PREPARED gid", "ROLLBACK PREPARED 'gid' x")) {
                assertEquals("$dialect: $sql", listOf(null), begins(sql))
            }
        }
    }

    /** Trino has no BEGIN or END statement: a routine body's END must not end a transaction. */
    fun testTrinoTransactionControl() {
        val transactions = StatementHeadDetector(StatementRules.TRINO_TRANSACTIONS, "trino")
        fun begins(sql: String) = transactions.detect(sql).map { (it.effect as StatementEffect.Transaction).begins }
        assertEquals(listOf(true, false, false, false, false), begins("START TRANSACTION; COMMIT; COMMIT WORK; ROLLBACK; ROLLBACK WORK"))
        assertEquals(emptyList<Boolean?>(), begins("CREATE FUNCTION f() RETURNS int BEGIN DECLARE x int; RETURN 1; END; END; BEGIN"))
        assertEquals(emptyList<Boolean?>(), begins("SELECT 'BEGIN'; COMMITTED; CREATE TABLE begin_log(x INT)"))
    }

    fun testNothingToDetect() {
        assertEquals(emptyList<DetectedStatement>(), duckdb.detect(null))
        assertEquals(emptyList<DetectedStatement>(), duckdb.detect("  "))
        assertEquals(emptyList<DetectedStatement>(), StatementHeadDetector(emptyList(), "duckdb").detect("LOAD x"))
    }
}
