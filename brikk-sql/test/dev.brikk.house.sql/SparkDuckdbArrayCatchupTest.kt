package dev.brikk.house.sql

import dev.brikk.house.sql.ast.ArraySlice
import dev.brikk.house.sql.ast.ConcatWs
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SparkDuckdbArrayCatchupTest {
    @Test
    fun sparkSliceKeepsItsLengthThroughParsingSerdeAndNativeRoundTrips() {
        for (dialect in listOf("spark2", "spark")) {
            val source = "SELECT SLICE(ARRAY(1, 2, 3), 2, 4) AS values"
            val tree = Dialects.forName(dialect).parseOne(source)
            val slice = tree.findAll<ArraySlice>().single()
            assertEquals(true, slice.args["is_length"])
            assertEquals("4", (slice.args["end"] as Literal).name)
            assertFalse("length" in slice.args)
            assertEquals(source, tree.sql(dialect))
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
            assertEquals("SELECT ARRAY_SLICE([1, 2, 3], 2, 5) AS values", tree.sql("duckdb"))
            assertEquals(source, tree.sql(dialect), "generation must not mutate the length")
        }
    }

    @Test
    fun nativeInclusiveSliceBoundsStayNativeWhileSparkLengthsBecomeInclusiveEnds() {
        for ((source, expected) in listOf(
            "SLICE(ARRAY(1, 2, 3), 1, 2)" to "ARRAY_SLICE([1, 2, 3], 1, 2)",
            "SLICE(ARRAY(1, 2, 3), 2, 4)" to "ARRAY_SLICE([1, 2, 3], 2, 5)",
            "SLICE(ARRAY(1, 2, 3), 3, 0)" to "ARRAY_SLICE([1, 2, 3], 3, 2)",
            "SLICE(ARRAY(1, 2, 3), 2, 2147483647)" to "ARRAY_SLICE([1, 2, 3], 2, 2147483648)",
            "SLICE(NULL, 2, 1)" to "ARRAY_SLICE(NULL, 2, 2)",
        )) {
            assertEquals("SELECT $expected", SqlFragment("SELECT $source", "spark").transpileTo("duckdb").sql)
        }
        val native = "SELECT ARRAY_SLICE([1, 2, 3], 2, 3)"
        assertEquals(native, Dialects.DUCKDB.parseOne(native).sql("duckdb"))
        val legacy = ArraySlice(args("this" to Column(args("this" to dev.brikk.house.sql.ast.toIdentifier("a"))),
            "start" to Literal.number(2), "length" to Literal.number(4)))
        assertEquals("ARRAY_SLICE(a, 2, 5)", legacy.sql("duckdb"))
        assertEquals("SLICE(a, 2, 4)", legacy.sql("spark"))
    }

    @Test
    fun sparkConcatWsArrayFlatteningIsSourceSpecificAndSurvivesSerde() {
        for (dialect in listOf("hive", "spark2", "spark")) {
            val source = "SELECT CONCAT_WS('-', ARRAY('a', NULL, '', 'b'), 'x')"
            val tree = Dialects.forName(dialect).parseOne(source)
            assertEquals(true, tree.findAll<ConcatWs>().single().args["flatten"])
            assertEquals(source, tree.sql(dialect))
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
            val result = SqlFragment(source, dialect).transpileTo("duckdb")
            assertTrue("ARRAY_TO_STRING" in result.sql, result.sql)
            assertTrue("LIST_CONCAT" in result.sql, result.sql)
            assertTrue(result.unsupportedMessages.isEmpty(), result.unsupportedMessages.toString())
        }
        val native = "SELECT CONCAT_WS('-', ['a', NULL, 'b'])"
        assertEquals(native, Dialects.DUCKDB.parseOne(native).sql("duckdb"))
        assertEquals("SELECT CONCAT_WS('-', 'a', 'b')",
            SqlFragment("SELECT CONCAT_WS('-', 'a', 'b')", "spark").transpileTo("duckdb").sql)
    }

    @Test
    fun typedArrayColumnsFlattenButUnresolvedMixedArgumentsAreDiagnosed() {
        val source = "SELECT CONCAT_WS('-', t.a, t.b) FROM t"
        val tree = annotateTypes(Dialects.SPARK.parseOne(source),
            schema = mapOf("t" to mapOf("a" to "ARRAY<TEXT>", "b" to "TEXT")), dialect = Dialects.SPARK)
        val original = tree.copy()
        val output = Dialects.DUCKDB.generate(tree, sourceDialect = "spark")
        assertTrue("ARRAY_TO_STRING" in output && "CAST(t.a AS TEXT[])" in output, output)
        assertEquals(original, tree)
        val unknown = SqlFragment("SELECT CONCAT_WS('-', ARRAY('a'), t.b) FROM t", "spark").transpileTo("duckdb")
        assertTrue(unknown.unsupportedMessages.any { "unknown argument types" in it }, unknown.toString())
        val delimiter = SqlFragment("SELECT CONCAT_WS(t.sep, ARRAY('a', 'b')) FROM t", "spark").transpileTo("duckdb")
        assertTrue(delimiter.unsupportedMessages.any { "constant delimiter" in it }, delimiter.toString())
    }

    @Test
    fun sharedFlattenFlagAlsoPreservesTheCompanionTrinoRoute() {
        val source = "SELECT CONCAT_WS('-', ARRAY('a', NULL, 'b'), 'x')"
        val output = SqlFragment(source, "spark").transpileTo("trino")
        assertTrue("COALESCE(CAST(ARRAY['a', NULL, 'b'] AS ARRAY(VARCHAR)), ARRAY[])" in output.sql, output.sql)
        assertTrue("ARRAY[CAST('x' AS VARCHAR)]" in output.sql, output.sql)
        assertTrue(output.unsupportedMessages.isEmpty(), output.toString())
        assertEquals("SELECT CONCAT_WS('-', CAST('a' AS VARCHAR), CAST('b' AS VARCHAR))",
            SqlFragment("SELECT CONCAT_WS('-', 'a', 'b')", "spark").transpileTo("trino").sql)
        val unknown = SqlFragment("SELECT CONCAT_WS('-', ARRAY('a'), t.b) FROM t", "spark").transpileTo("trino")
        assertTrue(unknown.unsupportedMessages.any { "unknown argument types" in it }, unknown.toString())
    }
}
