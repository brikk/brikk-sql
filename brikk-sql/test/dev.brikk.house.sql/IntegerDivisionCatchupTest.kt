package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegerDivisionCatchupTest {
    @Test
    fun integerDivisionFallbackTruncatesBeforeRoundingCastsAndUsesBigint() {
        val tree = Dialects.HIVE.parseOne("SELECT x DIV y")
        for (target in listOf("presto", "trino")) {
            assertEquals("SELECT CAST(TRUNCATE(CAST(x AS DOUBLE) / y) AS BIGINT)", tree.sql(target))
        }
        assertEquals("SELECT CAST(x / y AS BIGINT)", Generator().generate(tree))
        assertEquals("SELECT x // y", tree.sql("duckdb"))
        assertEquals("SELECT DIV(x, y)", tree.sql("postgres"))
        assertEquals("SELECT DIV(x, y)", tree.sql("bigquery"))
        for (target in listOf("mysql", "doris", "starrocks", "hive", "spark2", "spark")) {
            assertEquals("SELECT x DIV y", tree.sql(target), target)
        }
    }

    @Test
    fun typedDivisionWithIntegerCastsUsesAnUntypedTruncatedQuotientWithoutRecursion() {
        val tree = Dialects.POSTGRES.parseOne("SELECT CAST(7 AS BIGINT) / CAST(2 AS BIGINT)")
        val original = tree.copy()
        assertEquals("SELECT CAST(TRUNC(CAST(7 AS BIGINT) / CAST(2 AS BIGINT)) AS BIGINT)", tree.sql("duckdb"))
        assertEquals("SELECT CAST(7 AS SIGNED) DIV CAST(2 AS SIGNED)", tree.sql("mysql"))
        assertEquals("SELECT DIV(CAST(7 AS INT64), CAST(2 AS INT64))", tree.sql("bigquery"))
        assertEquals("SELECT CAST(7 AS BIGINT) / CAST(2 AS BIGINT)", tree.sql("trino"))
        assertEquals(original, tree)
        assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
    }

    @Test
    fun annotatedIntegerColumnsAreLoweredButUnknownOrFractionalTypesAreNotInvented() {
        val source = "SELECT t.x / t.y AS q FROM t"
        val tree = annotateTypes(Dialects.POSTGRES.parseOne(source),
            schema = mapOf("t" to mapOf("x" to "BIGINT", "y" to "BIGINT")), dialect = Dialects.POSTGRES)
        assertTrue(tree.findAll<Div>().single().isType(DType.BIGINT))
        val original = tree.copy()
        assertEquals("SELECT CAST(TRUNC(t.x / t.y) AS BIGINT) AS q FROM t", tree.sql("duckdb"))
        assertEquals("SELECT CAST(TRUNCATE(t.x / t.y, 0) AS SIGNED) AS q FROM t", tree.sql("mysql"))
        assertEquals("SELECT CAST(TRUNCATE(t.x / t.y, 0) AS BIGINT) AS q FROM t", tree.sql("doris"))
        assertEquals("SELECT CAST(t.x / t.y AS BIGINT) AS q FROM t", tree.sql("starrocks"))
        assertEquals("SELECT CAST(TRUNC(t.x / t.y) AS INT64) AS q FROM t", tree.sql("bigquery"))
        assertEquals(original, tree)
        val unknown = annotateTypes(Dialects.POSTGRES.parseOne(source), dialect = Dialects.POSTGRES)
        assertEquals(source, unknown.sql("duckdb"))
        val fractional = annotateTypes(Dialects.POSTGRES.parseOne(source),
            schema = mapOf("t" to mapOf("x" to "DOUBLE", "y" to "BIGINT")), dialect = Dialects.POSTGRES)
        assertEquals(source, fractional.sql("duckdb"))
    }

    @Test
    fun inferredSourceIntegerExpressionsDoNotAssumeTargetIntegerOperandTypes() {
        val tree = Dialects.POSTGRES.parseOne("SELECT FLOOR(7) / FLOOR(2)")
        // Model proven source integer types whose corresponding target function returns DOUBLE.
        tree.findAll<Floor>().forEach { it.typeSlot = DataType.build(DType.BIGINT) }
        assertEquals("SELECT CAST(TRUNC(FLOOR(7) / FLOOR(2)) AS BIGINT)", tree.sql("duckdb"))
    }

    @Test
    fun nativeMysqlDivPreservesPrecedenceIncludingNestedAndUnaryExpressions() {
        for ((source, expected) in listOf(
            "DIV(10, DIV(4, 2))" to "10 DIV (4 DIV 2)",
            "10 / DIV(4, 2)" to "10 / (4 DIV 2)",
            "300 * DIV(1000, 300) + 120" to "300 * (1000 DIV 300) + 120",
            "DIV(10 + 2, 3 + 1)" to "(10 + 2) DIV (3 + 1)",
            "DIV(10, 2) / DIV(4, 3)" to "10 DIV 2 / (4 DIV 3)",
            "-DIV(7, 2)" to "-(7 DIV 2)",
            "~DIV(7, 3)" to "~(7 DIV 3)",
        )) {
            val tree = Dialects.BIGQUERY.parseOne("SELECT $source")
            val original = tree.copy()
            for (target in listOf("mysql", "doris", "starrocks")) {
                val output = tree.sql(target)
                assertEquals("SELECT $expected", output, target)
                assertEquals(output, Dialects.forName(target).parseOne(output).sql(target))
            }
            assertEquals(original, tree)
        }
    }

    @Test
    fun mysqlTruncateAlwaysHasItsRequiredScaleAndNativeDivisionRemainsNative() {
        assertEquals("SELECT TRUNCATE(3.14159, 0)",
            Dialects.POSTGRES.parseOne("SELECT TRUNC(3.14159)").sql("mysql"))
        assertEquals("SELECT TRUNCATE(3.14159, 2)",
            Dialects.POSTGRES.parseOne("SELECT TRUNC(3.14159, 2)").sql("mysql"))
        for ((dialect, source) in listOf(
            "duckdb" to "SELECT 7 // 2, -7 // 2, NULL // 2, 7 // 0",
            "mysql" to "SELECT 7 DIV 2, -7 DIV 2, NULL DIV 2, 7 DIV 0",
            "postgres" to "SELECT 7 / 2, -7 / 2, NULL / 2, 7 / 0",
        )) assertEquals(source, Dialects.forName(dialect).parseOne(source).sql(dialect))
    }

    @Test
    fun targetCapabilitiesAndSafeTypedDivisionChooseNullRatherThanErrorPaths() {
        assertTrue(Dialects.DUCKDB.generator().safeIntDivision)
        for (target in listOf("duckdb", "mysql", "doris", "bigquery", "presto", "trino")) {
            assertTrue(Dialects.forName(target).generator().castToIntRounds, target)
        }
        assertFalse(Dialects.STARROCKS.generator().castToIntRounds)
        val tree = annotateTypes(Dialects.POSTGRES.parseOne("SELECT 7 / 0"), dialect = Dialects.POSTGRES)
        assertEquals("SELECT CAST(TRUNC(7 / 0) AS BIGINT)", tree.sql("duckdb"))
        tree.findAll<Div>().single().set("safe", true)
        assertEquals("SELECT CAST(TRUNC(7 / NULLIF(0, 0)) AS BIGINT)", tree.sql("duckdb"))
        val scalar = SqlFragment("SELECT 7.0 / 2", "duckdb").transpileTo("trino")
        assertEquals("SELECT CAST(7.0 AS DOUBLE) / 2", scalar.sql)
        assertTrue(scalar.unsupportedMessages.isEmpty(), scalar.toString())
    }
}
