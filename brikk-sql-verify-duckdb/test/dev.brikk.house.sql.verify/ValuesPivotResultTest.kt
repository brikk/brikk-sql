package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.generator.Generator
import java.sql.DriverManager
import kotlin.test.*

internal class ValuesPivotResultTest {
    @Test fun nativeAndLoweredValuesPivotKeepRowsAndNativeOutputColumns() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fun observe(sql: String): Pair<List<String>, List<List<String?>>> = c.createStatement().use { s -> s.executeQuery(sql).use { r ->
                (1..r.metaData.columnCount).map(r.metaData::getColumnLabel) to buildList {
                    while (r.next()) add((1..r.metaData.columnCount).map(r::getString))
                }
            } }
            for ((sql, expected) in listOf(
                "SELECT * FROM (VALUES (1,10),(2,20)) AS t(k,v) PIVOT(MAX(v) FOR k IN (1 AS one,2 AS two))" to
                    (listOf("one", "two") to listOf(listOf("10", "20"))),
                "SELECT * FROM (VALUES ('g',1,10),('g',2,20),('h',1,30)) AS t(g,k,v) PIVOT(MAX(v) FOR k IN (1 AS one,2 AS two)) ORDER BY g" to
                    (listOf("g", "one", "two") to listOf(listOf("g", "10", "20"),listOf("h", "30", null))),
            )) {
                assertEquals(expected, observe(sql))
                val tree = Dialects.DUCKDB.parseOne(sql)
                val before = Serde.dump(tree)
                for (output in listOf(SqlFragment(sql, "duckdb").toExecutable("duckdb").sql,
                    Serde.loadExpression(before).sql("duckdb"), object : Generator() { override val valuesAsTable = false }.generate(tree))) {
                    // Forced generic SELECT/UNION lowering is replayed on DuckDB,
                    // not a new dialect registration or live Redshift qualification.
                    assertEquals(expected, observe(output), output)
                }
                assertEquals(before, Serde.dump(tree))
            }
        }
    }
    @Test fun valuesLocalOrderLimitOffsetAreAppliedBeforePivotInBothPaths() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val tree = Dialects.DUCKDB.parseOne("SELECT * FROM (VALUES (1,10),(2,20)) AS t(k,v) PIVOT(MAX(v) FOR k IN (1 AS one,2 AS two))")
            val values = tree.findAll<Values>().single()
            values.set("order", Order(args("expressions" to listOf(Ordered(args("this" to Literal.number("2"), "desc" to true))))))
            values.set("limit", Limit(args("expression" to Literal.number("1"))))
            values.set("offset", Offset(args("expression" to Literal.number("1"))))
            for (target in listOf("duckdb", "lowered")) for (pretty in listOf(false,true)) {
                val generator = if (target == "lowered") object : Generator(pretty = pretty) { override val valuesAsTable = false }
                    else Dialects.DUCKDB.generator(pretty = pretty)
                val output = generator.generate(tree)
                c.createStatement().use { s -> s.executeQuery(output).use { r ->
                    assertTrue(r.next()); assertEquals(10,r.getInt(1)); assertNull(r.getObject(2)); assertFalse(r.next())
                    assertEquals("INTEGER",r.metaData.getColumnTypeName(1))
                } }
            }
        }
    }
}
