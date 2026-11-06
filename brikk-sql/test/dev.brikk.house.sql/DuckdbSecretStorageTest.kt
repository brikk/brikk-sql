package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbSecretStorageTest {
    @Test fun secretStorageAndOptionsRemainExplicitRawSqlWithoutInventedAstOrForeignMapping() {
        for (head in listOf("CREATE SECRET audit IN connection", "CREATE TEMPORARY SECRET audit IN connection",
            "CREATE PERSISTENT SECRET audit", "CREATE OR REPLACE SECRET audit IN connection")) {
            val sql = "$head (TYPE http,BEARER_TOKEN 'synthetic-test-only',SCOPE 'https://audit.invalid')"
            // Boolean assertions avoid printing raw author SQL or serialized option values.
            val tree = try { parseDuckdbNativeOne(sql) } catch (_: Exception) { throw AssertionError("Secret syntax could not be retained") }
            assertTrue(tree is Command, "Unmodeled secret options must remain opaque")
            assertTrue(tree == Serde.load(Serde.dump(tree)), "Secret copy/serde changed")
            assertTrue(tree.copy().sql("duckdb") == sql, "Secret source/options changed")
            val result = SqlFragment(sql, "duckdb").toExecutable("duckdb")
            assertTrue(result.isRawPassthroughStatement && result.sql == sql, "Raw secret contract changed")
            assertTrue(runCatching { SqlFragment(sql, "duckdb").toExecutable("trino") }.isFailure, "Foreign secret execution must refuse")
        }
    }
}
