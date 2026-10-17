package dev.brikk.house.sql

import dev.brikk.house.sql.ast.IgnoreNulls
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarrocksWindowCatchupTest {
    @Test
    fun nativeWindowIgnoreNullsUsesTheFirstArgumentAndPreservesOffsetsAndDefaults() {
        for (function in listOf("FIRST_VALUE", "LAST_VALUE", "LAG", "LEAD")) {
            val source = "SELECT $function(x IGNORE NULLS) OVER (ORDER BY t)"
            val ast = Dialects.STARROCKS.parseOne(source)
            assertTrue(ast.findAll<IgnoreNulls>().any(), source)
            assertEquals(source, ast.sql("starrocks"))
        }
        for (function in listOf("LAG", "LEAD")) {
            for (args in listOf("x IGNORE NULLS, 2", "x IGNORE NULLS, 2, 0")) {
                val source = "SELECT $function($args) OVER (ORDER BY t)"
                assertEquals(source, Dialects.STARROCKS.parseOne(source).sql("starrocks"))
            }
        }
        val suffix = "SELECT LAG(x, 1, 0) IGNORE NULLS OVER (ORDER BY t)"
        assertEquals("SELECT LAG(x IGNORE NULLS, 1, 0) OVER (ORDER BY t)",
            Dialects.STARROCKS.parseOne(suffix).sql("starrocks"))
        assertEquals(TokenType.VAR, Dialects.STARROCKS.tokenize("IGNORE").single().tokenType)
    }

    @Test
    fun crossDialectIgnoreNullsIsRetainedWhileRespectNullsUsesTheNativeDefault() {
        val duckdb = SqlFragment("SELECT LEAD(x, 1, 0 IGNORE NULLS) OVER (ORDER BY t NULLS FIRST)", "duckdb")
            .transpileTo("starrocks")
        assertEquals("SELECT LEAD(x IGNORE NULLS, 1, 0) OVER (ORDER BY t)", duckdb.sql)
        assertTrue(duckdb.unsupportedMessages.isEmpty(), duckdb.toString())
        val ignore = SqlFragment("SELECT LAST_VALUE(x IGNORE NULLS) OVER (ORDER BY t)", "bigquery")
            .transpileTo("starrocks")
        assertEquals("SELECT LAST_VALUE(x IGNORE NULLS) OVER (ORDER BY t)", ignore.sql)
        assertTrue(ignore.unsupportedMessages.isEmpty(), ignore.toString())
        val respect = SqlFragment("SELECT LAST_VALUE(x RESPECT NULLS) OVER (ORDER BY t)", "bigquery")
            .transpileTo("starrocks")
        assertEquals("SELECT LAST_VALUE(x) OVER (ORDER BY t)", respect.sql)
        assertTrue(respect.unsupportedMessages.isEmpty(), respect.toString())
    }
}
