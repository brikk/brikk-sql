package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.optimizer.buildScope
import dev.brikk.house.sql.optimizer.lineage
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class ValuesPivotCatchupTest {
    private val sql = "SELECT * FROM (VALUES (1, 10), (2, 20)) AS t(k, v) PIVOT(MAX(v) FOR k IN (1 AS one, 2 AS two))"
    @Test fun parenthesizedAndBareValuesKeepAliasColumnsAndPivot() {
        Values::class.java.getConstructor(java.util.Map::class.java)
        Values::class.java.getConstructor()
        for (source in listOf(sql, sql.replace("(VALUES (1, 10), (2, 20))", "VALUES (1, 10), (2, 20)"))) {
            val tree = parseOne(source, "duckdb")
            val values = tree.findAll<Values>().single()
            assertEquals(listOf("k", "v"), values.aliasColumnNames)
            assertEquals(1, (values.args["pivots"] as List<*>).size)
            assertEquals(sql, tree.sql("duckdb"))
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
        }
    }
    @Test fun nativeAndLoweredGeneratorsDoNotDropPivotsOrMutateValues() {
        val tree = parseOne(sql, "duckdb")
        val before = Serde.dump(tree)
        val parents = tree.walk().map { it to it.parent }.toList()
        for (target in listOf("duckdb", "spark", "lowered")) for (pretty in listOf(false, true)) {
            val generator = if (target == "lowered") object : Generator(pretty = pretty) { override val valuesAsTable = false }
                else Dialects.forName(target).generator(pretty = pretty)
            val output = generator.generate(tree, copy = false)
            assertContains(output, "PIVOT")
            if (target == "lowered") assertContains(output, "UNION ALL")
            assertEquals(before, Serde.dump(tree))
            for ((node, parent) in parents) assertSame(parent, node.parent)
            parseOne(output, if (target == "lowered") "duckdb" else target)
        }
    }
    @Test fun scopeOutputTypesAndLineageSeeThePivotedColumns() {
        val tree = parseOne(sql, "duckdb")
        val scope = assertNotNull(buildScope(tree))
        assertEquals(1, scope.pivots.size)
        assertEquals(listOf("k", "v"), scope.udtfScopes.single().outerColumns)
        val shape = SqlFragment(sql, "duckdb").outputShape()
        assertEquals(listOf("one", "two"), shape.columns.map { it.name })
        assertEquals(listOf("INT", "INT"), shape.columns.map { it.type })
        assertEquals("one", lineage("one", sql, dialect = Dialects.DUCKDB).name)
    }
    @Test fun valuesLocalPaginationIsRetainedWhenLoweredToSelectUnion() {
        val tree = parseOne(sql, "duckdb")
        val values = tree.findAll<Values>().single()
        values.set("order", Order(args("expressions" to listOf(Ordered(args("this" to Literal.number("2"), "desc" to true))))))
        values.set("limit", Limit(args("expression" to Literal.number("1"))))
        values.set("offset", Offset(args("expression" to Literal.number("1"))))
        val before = Serde.dump(tree)
        for (target in listOf("duckdb", "lowered")) {
            val generator = if (target == "lowered") object : Generator() { override val valuesAsTable = false }
                else Dialects.DUCKDB.generator()
            val output = generator.generate(tree, copy = false)
            assertContains(output, "ORDER BY 2 DESC")
            assertContains(output, "LIMIT 1")
            assertContains(output, "OFFSET 1")
            assertContains(output, "PIVOT")
            assertEquals(before, Serde.dump(tree))
        }
        val partial = parseOne("SELECT * FROM (VALUES (1, 10)) AS t(k)", "duckdb")
        val preview = object : Generator() { override val valuesAsTable = false }.generate(partial)
        assertContains(preview, "1 AS k, 10")
        val strict = object : Generator() { override val valuesAsTable = false }.apply { forExecution = true }
        assertFailsWith<UnsupportedError> { strict.generate(partial) }
    }
    @Test fun unsupportedTargetsRefuseRatherThanSilentlyDroppingPivot() {
        for (target in listOf("postgres", "mysql", "clickhouse", "trino", "bigquery")) {
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toExecutable(target) }
        }
    }
}
