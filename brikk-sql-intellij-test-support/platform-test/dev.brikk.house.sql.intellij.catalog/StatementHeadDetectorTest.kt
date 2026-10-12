package dev.brikk.house.sql.intellij.catalog

import junit.framework.TestCase

class StatementHeadDetectorTest : TestCase() {
    private val duckdb = StatementHeadDetector(StatementRules.DUCKDB)
    private val trino = StatementHeadDetector(StatementRules.TRINO)
    private val doris = StatementHeadDetector(StatementRules.DORIS, quoteChars = "`")

    private fun StatementHeadDetector.heads(script: String) = detect(script).map { it.rule.pattern }

    fun testExtensionStatementsChangeTheFunctionInventory() {
        assertEquals(listOf("INSTALL", "LOAD", "FORCE INSTALL"), duckdb.heads("install httpfs; LOAD httpfs;\n  FORCE  INSTALL spatial"))
        assertTrue(duckdb.detect("load spatial").single().effect == StatementEffect.FunctionInventory)
    }

    fun testOnlyWholeLeadingWordsMatch() {
        assertEquals(emptyList<String>(), duckdb.heads("SELECT * FROM install_log; INSTALLER x; install2 y; FORCE CHECKPOINT; UPDATE EXTENSIONS"))
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
        val pattern = "CREATE [OR REPLACE] [TEMP|TEMPORARY] MACRO|FUNCTION"
        for (sql in listOf("CREATE MACRO m(x) AS x", "create or replace temp macro m(x) AS x", "CREATE TEMPORARY FUNCTION f() AS 1")) {
            assertEquals(sql, listOf(pattern), duckdb.heads(sql))
        }
        assertEquals(emptyList<String>(), duckdb.heads("CREATE OR MACRO m() AS 1"))
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
            assertTrue(pattern, runCatching { StatementRule(pattern, StatementEffect.FunctionInventory) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    fun testNothingToDetect() {
        assertEquals(emptyList<DetectedStatement>(), duckdb.detect(null))
        assertEquals(emptyList<DetectedStatement>(), duckdb.detect("  "))
        assertEquals(emptyList<DetectedStatement>(), StatementHeadDetector(emptyList()).detect("LOAD x"))
    }
}
