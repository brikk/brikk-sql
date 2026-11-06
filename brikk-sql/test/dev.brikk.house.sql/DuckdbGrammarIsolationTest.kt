package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbGrammarIsolationTest {
    @Test fun extensionStageNamesRemainIdentifiersWithoutAProcessGlobalKeywordRegistry() {
        for (name in listOf("extend", "rename", "psql", "graph", "match", "vertex")) {
            val sql = "SELECT $name FROM $name"
            for (tree in listOf(Dialects.DUCKDB.parseOne(sql), parseDuckdbNativeOne(sql))) {
                assertIs<Select>(tree)
                assertEquals(name, assertIs<Column>(tree.expressionsArg.single()).name)
                assertEquals(name, tree.findAll<Table>().single().name)
                assertEquals(tree, Serde.load(Serde.dump(tree)))
                assertEquals(tree.sql("duckdb"), tree.copy().sql("duckdb"))
            }
        }
    }
    @Test fun unknownExtensionStatementsNeverGainExecutableSemantics() {
        for (sql in listOf("PSQL EXTEND x", "CREATE PROPERTY GRAPH g")) {
            // A parser failure or opaque preview is fine, an executable invented AST is not.
            val result = runCatching { SqlFragment(sql, "duckdb").toExecutable("duckdb") }
            result.getOrNull()?.let { assertTrue(it.isRawPassthroughStatement, sql) }
        }
        assertIs<Select>(parseDuckdbNativeOne("SELECT 'PSQL EXTEND; CREATE PROPERTY GRAPH' AS text"))
    }
}
