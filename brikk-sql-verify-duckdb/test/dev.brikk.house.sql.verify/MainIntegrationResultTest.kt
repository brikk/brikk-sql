package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.generator.Generator
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.*

internal class MainIntegrationResultTest {
    @Test fun valuesPaginationStaysInsideTheRelationBeforeItsAliasAndNestedJoins() {
        val source = "SELECT * FROM (VALUES (1)) t1(a) LEFT JOIN (VALUES (1), (2)) t2(b) JOIN (VALUES (1)) t3(c) ON t2.b = t3.c ON t1.a = t2.b"
        DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
            setProperty("enable_external_access", "false")
        }).use { connection -> connection.createStatement().use { statement ->
            fun rows(sql: String): List<List<String?>> = statement.executeQuery(sql).use { result ->
                buildList { while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) }) }
            }
            for (descending in listOf(false, true)) {
                val tree = parseDuckdbNativeOne(source)
                val values = tree.findAll<Values>().single { it.alias == "t2" }
                values.set("order", Order(args("expressions" to listOf(Ordered(args(
                    "this" to Literal.number("1"), "desc" to descending,
                ))))))
                values.set("limit", Limit(args("expression" to Literal.number("1"))))
                values.set("offset", Offset(args("expression" to Literal.number("0"))))
                val expected = listOf(if (descending) listOf("1", null, null) else listOf("1", "1", "1"))
                for (ast in listOf(tree, tree.copy(), Serde.loadExpression(Serde.dump(tree)))) {
                    val before = Serde.dump(ast)
                    for (lower in listOf(false, true)) {
                        val generator = object : Generator() { override val valuesAsTable get() = !lower }
                        val sql = generator.generate(ast, copy = false)
                        assertEquals(expected, rows(sql), sql)
                        assertEquals(before, Serde.dump(ast))
                    }
                }
            }
        } }
    }

    @Test fun compactCaseNumbersMatchIndependentNativeResults() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
            for (number in listOf("13", "1.5", ".5", "1e3", "1E+3", "1e-3", ".5e2")) {
                val source = "SELECT CASE WHEN TRUE THEN ${number}END"
                val tree = parseDuckdbNativeOne(source)
                for (ast in listOf(tree, tree.copy(), Serde.loadExpression(Serde.dump(tree)))) {
                    statement.executeQuery(Generator().generate(ast)).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(number.toDouble(), rows.getDouble(1), source)
                        assertFalse(rows.next())
                    }
                }
            }
        } }
    }
}
