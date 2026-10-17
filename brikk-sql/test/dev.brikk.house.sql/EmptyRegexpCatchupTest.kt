package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyRegexpCatchupTest {
    @Test
    fun canonicalEmptyChecksUseTheOriginalPatternBeforeEmbeddingFlags() {
        for (call in listOf("REGEXP_COUNT(s, p, 1, 'im')", "REGEXP_INSTR(s, p, 1, 1, 0, 'im')")) {
            val tree = Dialects.BASE.parseOne("SELECT $call FROM t")
            val original = Serde.dump(tree)
            val generator = Dialects.DUCKDB.generator()
            val output = generator.generate(tree)
            assertTrue("WHEN p = '' THEN 0" in output, output)
            assertTrue("'(?im)' || p" in output, output)
            assertFalse("WHEN '(?im)' || p = ''" in output, output)
            assertTrue(output.indexOf("s IS NULL") < output.indexOf("p = ''"), output)
            assertTrue(generator.unsupportedMessages.isEmpty(), generator.unsupportedMessages.toString())
            assertEquals(original, Serde.dump(tree), "generation must not reparent source arguments")
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
            val normalized = Dialects.DUCKDB.parseOne(output).sql("duckdb")
            assertEquals(normalized, Dialects.DUCKDB.parseOne(normalized).sql("duckdb"))
        }
    }

    @Test
    fun nativeSourcePoliciesDoNotApplyTheCanonicalZeroRuleToAllDialects() {
        val count = "SELECT REGEXP_COUNT('abc', '')"
        for (source in listOf("postgres", "trino", "spark")) {
            val result = SqlFragment(count, source).transpileTo("duckdb")
            assertFalse("WHEN '' = '' THEN 0" in result.sql, result.sql)
            assertTrue(result.unsupportedMessages.isEmpty(), result.toString())
        }
        assertTrue("WHEN '' = '' THEN 0" in Dialects.BASE.parseOne(count).sql("duckdb"))
        val postgres = SqlFragment("SELECT REGEXP_INSTR('abc', '', 1, 1, 0, 'i')", "postgres").transpileTo("duckdb")
        assertFalse("WHEN '' = '' THEN 0" in postgres.sql, postgres.sql)
        assertTrue(postgres.unsupportedMessages.isEmpty(), postgres.toString())
        val mysql = SqlFragment("SELECT REGEXP_INSTR('abc', '', 1, 1, 0, 'i')", "mysql").transpileTo("duckdb")
        assertTrue("MySQL REGEXP_INSTR rejects an empty pattern" in mysql.sql, mysql.sql)
        assertTrue(mysql.unsupportedMessages.isEmpty(), mysql.toString())
    }

    @Test
    fun unsupportedFlagsGroupsAndRestartedPostgresAnchorsAreDiagnosed() {
        for ((source, dialect, diagnostic) in listOf(
            Triple("SELECT REGEXP_COUNT(s, p, 1, 'iz') FROM t", "", "Regexp flags"),
            Triple("SELECT REGEXP_COUNT(s, p, 1, f) FROM t", "", "Non-literal regexp flags"),
            Triple("SELECT REGEXP_INSTR(s, p, 1, 1, 0, 'i', 1) FROM t", "", "capture-group positions"),
            Triple("SELECT REGEXP_COUNT('abc', '^b', 2)", "postgres", "PostgreSQL start anchors"),
            Triple("SELECT REGEXP_INSTR('abc', '^b', 2)", "postgres", "PostgreSQL start anchors"),
            Triple("SELECT REGEXP_INSTR('abc', '^')", "", "nonempty zero-width"),
            Triple("SELECT REGEXP_COUNT('abc', 'a', 1)", "trino", "only supports subject and pattern"),
        )) {
            val result = SqlFragment(source, dialect).transpileTo("duckdb")
            assertTrue(result.unsupportedMessages.any { diagnostic in it }, result.toString())
        }
    }

    @Test
    fun runtimeOptionExpressionsDoNotFabricateUnknownSourceTypeEvidence() {
        val source = Dialects.POSTGRES.parseOne("SELECT REGEXP_INSTR(t.s, t.p, t.pos, t.occ, t.opt, 'i') FROM t")
        val tree = annotateTypes(source, schema = mapOf("t" to mapOf("s" to "TEXT", "p" to "TEXT",
            "pos" to "INT", "occ" to "INT", "opt" to "INT")), dialect = Dialects.POSTGRES)
        val original = tree.copy()
        assertTrue(tree.findAll<RegexpInstr>().single().isType(DType.UNKNOWN))
        val generator = Dialects.DUCKDB.generator(sourceDialect = "postgres")
        val output = generator.generate(tree)
        assertTrue("t.opt = 1" in output && "AS BIGINT" in output, output)
        assertTrue("t.opt IS NULL" in output, output)
        assertTrue(generator.unsupportedMessages.any { "PostgreSQL start anchors" in it },
            "Dynamic patterns plus a starting position cannot prove anchor portability")
        assertEquals(original, tree)
    }

    @Test
    fun nativeDuckdbMatchesReplaceAndExtractAllDoNotAcquireTheZeroRule() {
        for (source in listOf(
            "SELECT REGEXP_MATCHES('abc', '', 'i')",
            "SELECT REGEXP_REPLACE('abc', '', '-', 'g')",
            "SELECT REGEXP_EXTRACT_ALL('abc', '(?i)')",
        )) assertEquals(source, SqlFragment(source, "duckdb").transpileTo("duckdb").sql)
    }
}
