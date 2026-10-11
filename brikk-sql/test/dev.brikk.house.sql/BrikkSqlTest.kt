package dev.brikk.house.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class BrikkSqlTest {
    @Test
    fun moduleIsWired() {
        assertEquals("brikk-sql", BrikkSql.NAME)
        assertEquals("v30.22.0-5-ga00dc2e5c", BrikkSql.SQLGLOT_PIN)
    }
}
