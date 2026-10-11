package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArraySubqueryCatchupTest {
    @Test
    fun arraysKeepEveryScalarSubqueryElementInEitherSupportedDirection() {
        for (source in listOf(
            "SELECT [(SELECT 1), 2]", "SELECT [1, (SELECT 2)]",
            "SELECT [(SELECT 1), (SELECT 2), 3]", "SELECT [COALESCE((SELECT 1), 0), 2]",
        )) {
            for (read in listOf("duckdb", "bigquery")) {
                val tree = Dialects.forName(read).parseOne(source)
                val original = Serde.dump(tree)
                val size = tree.findAll<ArrayNode>().first().expressionsArg.size
                for (write in listOf("duckdb", "bigquery")) {
                    val output = tree.sql(write)
                    assertEquals(source, output)
                    assertEquals(size, Dialects.forName(write).parseOne(output).findAll<ArrayNode>().first().expressionsArg.size)
                    assertEquals(original, Serde.dump(tree))
                }
            }
        }
    }

    @Test
    fun scalarValueConstructorsAndRowQueryConstructorsRemainDistinctThroughSerde() {
        for ((source, valueConstructor) in listOf(
            "SELECT [(SELECT 1)]" to true,
            "SELECT ARRAY(SELECT 1 WHERE FALSE)" to false,
            "SELECT ARRAY((SELECT 1 WHERE FALSE))" to false,
        )) {
            for (read in listOf("duckdb", "bigquery")) {
                val tree = Dialects.forName(read).parseOne(source)
                assertEquals(valueConstructor, tree.findAll<ArrayNode>().single().args["value_constructor"] == true)
                val copy = Serde.loadExpression(Serde.dump(tree))
                assertEquals(tree, copy)
                for (write in listOf("duckdb", "bigquery")) assertEquals(source, copy.sql(write))
            }
        }
        assertEquals("SELECT [(SELECT 1)]", SqlFragment("SELECT LIST_VALUE((SELECT 1))", "duckdb").transpileTo("duckdb").sql)
        assertEquals("SELECT [(SELECT 1), 2]", SqlFragment("SELECT LIST_VALUE((SELECT 1), 2)", "duckdb").transpileTo("bigquery").sql)
    }

    @Test
    fun nestedQueriesInsideValuesAreNotPromotedToRowArrayConstructors() {
        for (source in listOf("SELECT [COALESCE((SELECT 1), 0)]", "SELECT [[(SELECT 1), 2]]")) {
            for (target in listOf("duckdb", "bigquery")) {
                val result = SqlFragment(source, "duckdb").transpileTo(target)
                assertEquals(source, result.sql)
                assertTrue(result.unsupportedMessages.isEmpty(), result.toString())
            }
        }
        val source = "SELECT [STRUCT((SELECT 1) AS a), STRUCT(2 AS a)]"
        assertEquals(source, SqlFragment(source, "bigquery").transpileTo("bigquery").sql)
        val duckdb = SqlFragment(source, "bigquery").transpileTo("duckdb")
        assertTrue(duckdb.sql.startsWith("SELECT [") && "(SELECT 1)" in duckdb.sql && "2" in duckdb.sql, duckdb.sql)
        assertEquals(2, Dialects.DUCKDB.parseOne(duckdb.sql).findAll<ArrayNode>().single().expressionsArg.size)
        assertTrue(duckdb.unsupportedMessages.isEmpty(), duckdb.toString())
    }
}
