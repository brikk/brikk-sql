package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.*

internal class DuckdbSubqueryColonAliasResultTest {
    @Test fun authoredAndGeneratedSubqueryAliasesReturnTheSameRowsWithoutBindingDuringParsing() {
        DriverManager.getConnection("jdbc:duckdb:", Properties().apply { setProperty("enable_external_access", "false") }).use { c ->
            for ((sql, values) in listOf(
                "SELECT q.n FROM q: (SELECT 1 AS n)" to listOf(1),
                "FROM q: (SELECT 1 AS n) SELECT q.n" to listOf(1),
                "SELECT q.n,r.m FROM q: (SELECT 1 AS n) JOIN r: (SELECT 2 AS m) ON q.n<r.m" to listOf(1, 2),
                "SELECT q.n FROM q: (SELECT r.n FROM r: (SELECT 3 AS n))" to listOf(3),
                "WITH c AS (SELECT 4 AS n) SELECT q.n FROM q: (FROM c SELECT n)" to listOf(4))) {
                val tree = parseDuckdbNativeOne(sql)
                for (text in listOf(sql, tree.copy().sql("duckdb"), assertIs<Expression>(Serde.load(Serde.dump(tree))).sql("duckdb"))) {
                    assertIs<SerializedSql.Parsed>(c.serializeSql(text))
                    c.createStatement().use { s ->
                        s.executeQuery(text).use { r ->
                            assertTrue(r.next())
                            assertEquals(values, values.indices.map {
                r.getInt(it + 1)
                            })
                            assertFalse(r.next())
                        }
                    }
                }
            }
            for (sql in listOf("SELECT * FROM q: (SELECT 1) AS r", "SELECT * FROM q(n): (SELECT 1)"))
                assertEquals("parser", assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
            c.createStatement().use { it.execute("CREATE SEQUENCE untouched") }
            assertIs<SerializedSql.Parsed>(c.serializeSql("SELECT q.n FROM q: (SELECT nextval('untouched') AS n)"))
            c.createStatement().use { s ->
                s.executeQuery("SELECT nextval('untouched')").use { r ->
                    assertTrue(r.next())
                    assertEquals(1, r.getInt(1))
                }
            }
        }
    }
}
