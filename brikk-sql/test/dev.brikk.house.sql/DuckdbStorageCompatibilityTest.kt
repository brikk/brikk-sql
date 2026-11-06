package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.parseParameterOccurrences
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.*

class DuckdbStorageCompatibilityTest {
    @Test fun storageOptionsAndCompatibilitySettingsStayAuthoredWithoutOpeningOrMigratingFiles() {
        for (sql in listOf("ATTACH 'never-opened.db' AS old_file (STORAGE_VERSION 'v1.5.0')",
            "ATTACH 'never-opened.db' AS latest_file (STORAGE_VERSION 'latest',READ_ONLY)",
            "SET storage_compatibility_version='v1.5.0'", "RESET storage_compatibility_version",
            "CHECKPOINT old_file", "FORCE CHECKPOINT old_file")) {
            val tree = parseDuckdbNativeOne(sql)
            assertEquals(tree, tree.copy())
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            val out = SqlFragment(sql, "duckdb").toExecutable("duckdb").sql
            assertEquals(out, parseDuckdbNativeOne(out).sql("duckdb"))
            if ("'v1.5.0'" in sql) assertTrue("'v1.5.0'" in out)
            if ("READ_ONLY" in sql) assertTrue("READ_ONLY" in out)
            if ("CHECKPOINT" in sql) {
                assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").isRawPassthroughStatement)
                assertFalse(" AS " in out)
            }
        }
        assertFailsWith<UnsupportedError> { parseParameterOccurrences("CHECKPOINT ?", "duckdb") }
        assertFailsWith<UnsupportedError> { SqlFragment("CHECKPOINT old_file", "duckdb").toExecutable("trino") }
    }
}
