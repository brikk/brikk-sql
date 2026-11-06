package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

internal class DuckdbAnalyseResultTest {
    @Test fun finalEngineAcceptsBothSpellingsAndGeneratedStructuredStatisticsStatements() {
        DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
            setProperty("enable_external_access", "false")
            setProperty("autoload_known_extensions", "false")
            setProperty("autoinstall_known_extensions", "false")
        }).use { c ->
            fun execute(sql: String) = c.createStatement().use { s ->
                if (s.execute(sql)) s.resultSet.use { r -> while (r.next()) { /* consume the single execution */ } }
            }
            execute("CREATE TABLE t(x INTEGER,y INTEGER)")
            execute("INSERT INTO t VALUES(1,2),(3,4)")
            execute("CREATE TABLE \"odd.name\"(\"x.y\" INTEGER)")
            execute("CREATE SEQUENCE untouched")
            for (spelling in listOf("ANALYZE", "ANALYSE")) for (tail in listOf("", " t", " memory.main.t(x,y)", " \"odd.name\"(\"x.y\")")) {
                val original = spelling + tail
                val tree = parseDuckdbNativeOne(original)
                for (sql in listOf(original, tree.copy().sql("duckdb"), assertIs<Expression>(Serde.load(Serde.dump(tree))).sql("duckdb"))) {
                    assertEquals("not implemented", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
                    execute(sql)
                }
            }
            for (sql in listOf("EXPLAIN ANALYSE SELECT nextval('untouched')", "EXPLAIN (ANALYSE) SELECT nextval('untouched')")) {
                assertEquals("not implemented", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
                assertIs<DuckdbExplain>(parseDuckdbNativeOne(sql))
            }
            c.createStatement().use { s -> s.executeQuery("SELECT nextval('untouched'),sum(x),sum(y) FROM t").use { r ->
                assertTrue(r.next())
                    assertEquals(1, r.getInt(1))
                    assertEquals(4, r.getInt(2))
                    assertEquals(6, r.getInt(3))
            } }
            for (sql in listOf("ANALYSE t()", "ANALYSE t,t", "ANALYSE TABLE t", "ANALYSE (VERBOSE) t")) {
                val result = assertIs<SerializedSql.Failed>(c.serializeSql(sql), sql)
                assertEquals("parser", result.type, sql)
            }
            // Native syntax/binding/implementation errors are separate from structured parse support.
            assertFailsWith<SQLException> { execute("ANALYSE missing") }
            val verbose = assertFailsWith<SQLException> { execute("ANALYSE VERBOSE t") }
            assertTrue("not implemented" in verbose.message.orEmpty().lowercase())
            for (sql in listOf("EXPLAIN ANALYSE SELECT 1", "EXPLAIN (ANALYSE) SELECT 1", "VACUUM ANALYSE t", "VACUUM ANALYZE t")) execute(sql)
        }
    }
}
