package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

internal class StarrocksLikeMapResultTest {
    @Test fun likeOrderAndNullsHaveNativeBooleanResults() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((call, expected) in listOf("LIKE('xyz','x%')" to true, "LIKE('x%','xyz')" to false,
                "LIKE(NULL,'x%')" to null, "LIKE('xyz',NULL)" to null, "LIKE('a_b','a_b')" to true)) {
                val sql = SqlFragment("SELECT $call", "starrocks").toExecutable("duckdb").sql
                c.createStatement().use { s -> s.executeQuery(sql).use { r ->
                    assertEquals("BOOLEAN", r.metaData.getColumnTypeName(1)); assertTrue(r.next())
                    val actual = r.getBoolean(1)
                    if (expected == null) assertTrue(r.wasNull()) else assertEquals(expected, actual, sql)
                } }
            }
        }
    }
    @Test fun mapPreviewsPreserveQualifiedKeysAndNativeLookupsWithoutClaimingCoercionEquivalence() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((source, expected) in listOf("SELECT MAP{'a':10,'b':20}['a']" to "10",
                "SELECT MAP{t.k:t.v}['a'] FROM (SELECT 'a' k, 10 v) t" to "10",
                "SELECT MAP{t.k:10}[1] FROM (SELECT 1 k) t CROSS JOIN (SELECT 2 k) u" to "10",
                "SELECT MAP{1:MAP{2:10}}[1][2]" to "10")) {
                assertFailsWith<UnsupportedError> { SqlFragment(source,"starrocks").toExecutable("duckdb") }
                val output = transpile(source,"starrocks","duckdb")
                c.createStatement().use { s -> s.executeQuery(output).use { r ->
                    assertTrue(r.next()); assertEquals(expected,r.getString(1),output)
                } }
            }
            for (sql in listOf("SELECT MAP{NULL:1}","SELECT MAP{'a':1,'a':2}")) {
                c.createStatement().use { s ->
                    val failure = assertFailsWith<SQLException> { s.executeQuery(sql) }
                    assertTrue(failure.message.orEmpty().contains("Map keys"),failure.message)
                }
            }
        }
    }
}
