package dev.brikk.house.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class BrikkSqlTest {
    @Test
    fun moduleIsWired() {
        assertEquals("brikk-sql", BrikkSql.NAME)
        assertEquals("v30.29.0", BrikkSql.SQLGLOT_PIN)
    }
}
