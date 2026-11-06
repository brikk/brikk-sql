package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.*

class DuckdbSetSchemaTest {
    @Test fun bareAndDelimitedNativeFormsKeepAnOpaqueNamespaceStringNotInventedPathParts() {
        for (value in listOf("finance", "memory.finance", "memory.\"finance.reports\"", "\"a'b\"")) {
            val quoted = "'${value.replace("'","''")}'"
            for (delimiter in listOf(" ", " = ", " TO ")) {
                val sql = "SET SCHEMA$delimiter$quoted"
                val tree = assertIs<dev.brikk.house.sql.ast.Set>(parseDuckdbNativeOne(sql))
                assertEquals(value, tree.findAll<Literal>().single().name)
                assertEquals(tree, Serde.load(Serde.dump(tree)))
                val rendered = tree.copy().sql("duckdb")
                assertIs<dev.brikk.house.sql.ast.Set>(parseDuckdbNativeOne(rendered))
                assertFalse(SqlFragment(sql, "duckdb").toExecutable("duckdb").isRawPassthroughStatement)
                assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable("trino") }
            }
        }
        // Static SQLGlot parsing retains its historical opaque fallback.
        assertIs<Command>(Dialects.DUCKDB.parseOne("SET SCHEMA 'finance'"))
    }
    @Test fun scriptsKeepTheStatementFollowingBareSetSchema() {
        val sql = "SET SCHEMA 'finance'; SELECT 7 AS n"
        val nodes = nativeDuckdbParser().parse(nativeDuckdbAnalysisDialect().tokenize(sql), sql).filterNotNull().filterNot { it is Semicolon }
        assertEquals(2, nodes.size)
        assertIs<dev.brikk.house.sql.ast.Set>(nodes[0])
        assertIs<Select>(nodes[1])
        assertFails { parseDuckdbNativeOne(sql) }
    }
}
