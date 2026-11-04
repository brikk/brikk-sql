package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.MysqlGenerator
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

/** SG29-008, upstream f814e4cb: these aliases mean population in the MySQL family. */
class MysqlStatisticalAggregateCatchupTest {
    @Test
    fun mysqlFamilyAliasesHavePopulationKindsWithoutChangingExplicitSampleKinds() {
        for (dialect in listOf("mysql", "doris", "starrocks")) {
            val tree = parseOne("SELECT STD(x), stddev(x), VaRiAnCe(x), STDDEV_POP(x), STDDEV_SAMP(x), VAR_POP(x), VAR_SAMP(x) FROM t", dialect)
            assertEquals(listOf("StddevPop", "StddevPop", "VariancePop", "StddevPop", "StddevSamp", "VariancePop", "Variance"),
                tree.expressionsArg.filterIsInstance<Expression>().map { it::class.simpleName }, dialect)
            assertEquals(tree, Serde.loadExpression(Serde.dump(tree)))
        }
    }

    @Test
    fun sampleDefaultsInOtherProfilesDoNotBecomePopulation() {
        for (dialect in listOf("", "postgres", "duckdb", "trino", "bigquery")) {
            val tree = parseOne("SELECT STDDEV(x), VARIANCE(x) FROM t", dialect)
            assertIs<Stddev>(tree.expressionsArg[0], dialect)
            assertIs<Variance>(tree.expressionsArg[1], dialect)
        }
    }

    @Test
    fun mysqlFamilyPopulationAliasesGenerateExplicitPopulationForPeers() {
        for (source in listOf("mysql", "doris", "starrocks")) {
            for (target in listOf("mysql", "doris", "starrocks", "postgres", "duckdb", "trino")) {
                assertEquals("SELECT STDDEV_POP(x), STDDEV_POP(x), VAR_POP(x) FROM t",
                    transpile("SELECT STD(x), STDDEV(x), VARIANCE(x) FROM t", source, target), "$source -> $target")
            }
        }
    }

    @Test
    fun sampleDefaultsGenerateExplicitSampleInMysqlFamily() {
        for (source in listOf("postgres", "duckdb", "trino")) for (target in listOf("mysql", "doris", "starrocks")) {
            val input = "SELECT STDDEV(x), STDDEV_POP(x), STDDEV_SAMP(x), VARIANCE(x), VAR_POP(x), VAR_SAMP(x) FROM t"
            val output = transpile(input, source, target)
            assertEquals("SELECT STDDEV_SAMP(x), STDDEV_POP(x), STDDEV_SAMP(x), VAR_SAMP(x), VAR_POP(x), VAR_SAMP(x) FROM t", output)
            assertEquals(output, parseOne(output, target).sql(target))
        }
        // Previously serialized/programmatic canonical nodes keep their original kind;
        // target generation must not depend on reparsing a function name.
        assertEquals("STDDEV_SAMP(x)", Stddev(args("this" to Column(args("this" to Identifier(args("this" to "x")))))).sql("mysql"))
        assertEquals("VAR_POP(x)", VariancePop(args("this" to Column(args("this" to Identifier(args("this" to "x")))))).sql("mysql"))
    }

    @Test
    fun windowsDistinctAndGeneratedSourceSpansPreserveTheOwnedAst() {
        val source = "SELECT stddev(`😀x`) OVER (PARTITION BY g ORDER BY n ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS s FROM t"
        val tree = parseOne(source, "mysql")
        assertIs<StddevPop>(tree.findAll<Window>().single().thisArg)
        val before = Serde.dump(tree)
        val generator = Dialects.POSTGRES.generator(sourceDialect = "mysql").apply { trackSpans = true }
        val output = generator.generate(tree)
        assertContains(output, "STDDEV_POP")
        assertContains(output, "ROWS BETWEEN 1 PRECEDING AND CURRENT ROW")
        val column = tree.findAll<Identifier>().first { it.name == "😀x" }
        val emitted = output.indexOf("\"😀x\"")
        assertTrue(emitted >= 0, output)
        assertEquals(assertNotNull(SourceMap.sourcePosOf(column)).start,
            assertNotNull(assertNotNull(generator.lastSourceMap).sourcePosition(emitted)).start)
        assertEquals(before, Serde.dump(tree))
        val distinct = parseOne("SELECT STDDEV(DISTINCT x) FROM t", "postgres")
        assertContains(distinct.sql("mysql"), "DISTINCT x") // Preview preservation, not native DISTINCT qualification.
        assertEquals(distinct, Serde.loadExpression(Serde.dump(distinct)))
        for (name in listOf("STDDEV", "STDDEV_SAMP", "STDDEV_POP", "VARIANCE", "VAR_POP", "VAR_SAMP")) {
            for (target in listOf("mysql", "starrocks")) {
                assertFailsWith<dev.brikk.house.sql.generator.UnsupportedError>("$target/$name") {
                    SqlFragment("SELECT $name(DISTINCT x) FROM t", "postgres").toExecutable(target)
                }
            }
        }
    }

    @Test
    fun returnTypesStayDoubleAndAggregateNullabilityStaysConservative() {
        val shape = SqlFragment("SELECT STD(x) AS a, STDDEV(x) AS b, VARIANCE(x) AS c FROM t", "mysql").outputShape()
        assertEquals(listOf("DOUBLE", "DOUBLE", "DOUBLE"), shape.columns.map { it.type })
        assertTrue(shape.columns.all { it.nullable != false })
        val customOverrides: Map<kotlin.reflect.KClass<out Expression>, dev.brikk.house.sql.generator.GenMethod> =
            mapOf(Stddev::class to { _: Expression -> "custom_sample()" })
        assertEquals("custom_sample()", MysqlGenerator(overrides = customOverrides).generate(Stddev(args("this" to Literal.number("1")))))
    }
}
