package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class ArraySortCompatibilityCatchupTest {
    @Test fun bothPublishedNodeShapesAndSerdeNamesStayAvailable() {
        for ((name, fields) in mapOf("ArraySort" to listOf("this", "expression"), "SortArray" to listOf("this", "asc", "nulls_first"))) {
            val entry = ExpressionRegistry.entries.getValue(name)
            assertEquals(fields, entry.factory().argTypes.keys.toList())
            val tree = entry.factory().apply { set("this", parseOne("[3,1,NULL]", "duckdb")) }
            tree::class.java.getConstructor(java.util.Map::class.java)
            tree::class.java.getConstructor()
            assertEquals(name, Serde.loadExpression(Serde.dump(tree))::class.simpleName)
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
        }
    }
    @Test fun nativeReverseSortRetainsExplicitNullOrderWithoutMigratingItsNode() {
        for (head in listOf("LIST_REVERSE_SORT", "ARRAY_REVERSE_SORT")) for (nulls in listOf("NULLS FIRST", "NULLS LAST", "nulls first")) {
            val tree = assertIs<SortArray>(parseOne("$head([3,NULL,1], '$nulls')", "duckdb"))
            assertEquals(false, (tree.args["asc"] as BooleanNode).thisArg)
            assertEquals(nulls, (tree.args["nulls_first"] as Literal).name)
            val before = Serde.dump(tree)
            assertContains(Dialects.DUCKDB.generator(sourceDialect = "duckdb").generate(tree, copy = false), "'$nulls'")
            assertEquals(before, Serde.dump(tree))
            assertEquals(tree, Serde.loadExpression(before))
        }
    }
    @Test fun unsupportedComparatorNewShapeAndForeignIncomingSortStillRefuse() {
        val comparator = ArraySort(args("this" to parseOne("[3,1]", "duckdb"), "expression" to parseOne("(a,b) -> a-b", "trino")))
        val extended = ArraySort(args("this" to parseOne("[3,1]", "duckdb"), "asc" to BooleanNode(args("this" to false))))
        for (tree in listOf(comparator, extended)) {
            val strict = Dialects.DUCKDB.generator().apply { forExecution = true }
            assertFailsWith<UnsupportedError> { strict.generate(Serde.loadExpression(Serde.dump(tree))) }
        }
        for (read in listOf("trino", "spark")) assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT ARRAY_SORT(ARRAY[3,1])", read).toExecutable("duckdb")
        }
    }
}
