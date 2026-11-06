package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbCompatibilityModeTest {
    @Test fun compatibilitySettingIsSyntaxNotAnImplicitChangeToTheStaticDialect() {
        val sql = "SELECT list_transform([10,20],lambda x,i:i) AS indices"
        val before = parseDuckdbNativeOne(sql)
        for (value in listOf("none", "spark", "not_a_proved_mode")) {
            val setting = "SET dialect_compatibility_mode='$value'"
            val tree = assertIs<dev.brikk.house.sql.ast.Set>(parseDuckdbNativeOne(setting))
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            assertEquals(tree, parseDuckdbNativeOne(tree.copy().sql("duckdb")))
            assertFalse(SqlFragment(setting, "duckdb").toExecutable("duckdb").isRawPassthroughStatement)
            assertTrue(runCatching { SqlFragment(setting, "duckdb").toExecutable("trino") }.isFailure)
            assertEquals(before, parseDuckdbNativeOne(sql))
        }
        assertEquals("duckdb", nativeDuckdbAnalysisDialect().name)
    }
}
