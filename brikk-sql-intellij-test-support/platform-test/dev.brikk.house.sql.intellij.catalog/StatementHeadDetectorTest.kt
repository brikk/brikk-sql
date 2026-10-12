package dev.brikk.house.sql.intellij.catalog

import junit.framework.TestCase

class StatementHeadDetectorTest : TestCase() {
    private val duckdb = StatementHeadDetector(StatementRules.DUCKDB, "duckdb")
    private val trino = StatementHeadDetector(StatementRules.TRINO, "trino")
    private val doris = StatementHeadDetector(StatementRules.DORIS, "doris")

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
