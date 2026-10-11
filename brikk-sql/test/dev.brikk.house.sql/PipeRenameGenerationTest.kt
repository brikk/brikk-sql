package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipeRenameGenerationTest {
    private val targets = listOf("presto", "trino")
    private val complete = ShapeCatalog(tables = mapOf("t" to
        Shape.of("label" to "VARCHAR", "id" to "INT", "payload" to "BIGINT")))

    @Test
    fun unknownLayoutRefusesEveryExecutableGenerationRoute() {
        val fragment = SqlFragment("FROM t |> RENAME id AS x", "doris")
        val original = fragment.ast.copy()
        for (target in targets) {
            for (pretty in listOf(false, true)) {
                for (tracked in listOf(false, true)) {
                    val error = assertFailsWith<UnsupportedError>(target) {
                        fragment.toExecutable(target, pretty = pretty, trackSourceMap = tracked)
                    }
                    assertTrue(error.message.orEmpty().contains("RENAME"))
                    assertFailsWith<UnsupportedError>(target) {
                        fragment.transpileTo(target, pretty = pretty, trackSourceMap = tracked, desugarPipes = true)
                    }
                }
            }
            assertFailsWith<UnsupportedError>(target) { fragment.toStandardSql(target) }
            assertFailsWith<UnsupportedError>(target) { fragment.toSourcePreservingExecutable(target) }
        }
        assertEquals(original, fragment.ast)
    }

    @Test
    fun traitMinimumCanInformAnalysisButDoesNotCertifyExecutableRename() {
        val minimum = ShapeCatalog(tables = emptyMap(), slots = mapOf("src" to Shape.of("id" to "INT")))
        for (target in targets) {
            val fragment = SqlFragment("FROM src() |> RENAME id AS x", target)
            val original = fragment.ast.copy()
            // A minimum trait proves id exists, not that id is the whole input row.
            assertEquals(Shape.of("x" to "INT"), fragment.outputShape(minimum))
            assertFailsWith<UnsupportedError>(target) { fragment.toExecutable(target) }
            // Brikk-house Rel<T> erases the trait and uses this same-dialect runtime path.
            assertFailsWith<UnsupportedError>(target) { fragment.toSourcePreservingExecutable(target) }
            assertEquals(original, fragment.ast)
            // A known physical source does not close the row produced with an unknown source.
            assertFailsWith<UnsupportedError>(target) {
                SqlFragment("FROM t CROSS JOIN mystery |> RENAME id AS x", target)
                    .toStandardSql(target, complete, expandStars = true)
            }
        }
    }

    @Test
    fun nestedPipesAndDirectQualifiedStarGenerationCannotBypassRefusal() {
        for (target in targets) {
            for (source in listOf(
                "WITH p AS (FROM t |> RENAME id AS x) SELECT x FROM p",
                "SELECT x FROM (FROM t |> RENAME id AS x) AS p",
                "FROM t |> RENAME id AS x |> WHERE x > 0 |> SELECT x",
            )) {
                val fragment = SqlFragment(source, target)
                assertFailsWith<UnsupportedError>(source) { fragment.toExecutable(target) }
                assertFailsWith<UnsupportedError>(source) { fragment.toSourcePreservingExecutable(target) }
            }
            for (source in listOf("SELECT * RENAME (id AS x) FROM t", "SELECT t.* RENAME (id AS x) FROM t")) {
                val tree = Dialects.DUCKDB.parseOne(source)
                val original = tree.copy()
                assertFailsWith<UnsupportedError>(source) { Dialects.forName(target).generate(tree) }
                assertEquals(original, tree)
            }
        }
    }

    @Test
    fun completeLayoutsExpandInPlaceAndPreserveSimultaneousRenames() {
        for (target in targets) {
            for ((rename, names) in listOf(
                "id AS x" to listOf("label", "x", "payload"),
                "id AS payload, payload AS id" to listOf("label", "payload", "id"),
            )) {
                val fragment = SqlFragment("FROM t |> RENAME $rename", target)
                val original = fragment.ast.copy()
                val sql = fragment.toStandardSql(target, complete, expandStars = true)
                assertFalse("RENAME" in sql || "EXCEPT" in sql, sql)
                val output = SqlFragment(sql, target)
                assertEquals(names, output.outputShape(complete).names(), sql)
                assertTrue(output.ast.findAll<Star>().none(), sql)
                assertEquals(emptyList(), output.toExecutable(target).unsupportedMessages)
                assertEquals(original, fragment.ast)
            }
        }
    }

    @Test
    fun nativeDuckdbRenameAndOrdinaryTargetStarsRemainAvailable() {
        val source = "FROM t |> RENAME id AS x"
        assertTrue("* RENAME (id AS x)" in SqlFragment(source, "duckdb").toExecutable("duckdb").sql)
        for (target in targets) {
            val output = SqlFragment("FROM t |> SELECT *", target).toExecutable(target)
            assertTrue("SELECT *" in output.sql)
            assertEquals(emptyList(), output.unsupportedMessages)
        }
    }

    @Test
    fun explicitPipeFormattingIsNotChangedIntoExecutableLowering() {
        val source = "FROM t |> RENAME id AS x"
        for (target in targets) {
            assertEquals(source, SqlFragment(source, "doris").transpileTo(target, desugarPipes = false).sql)
        }
    }

    @Test
    fun malformedTrailingIdentifierIsStillAParseErrorRatherThanGeneratorInput() {
        for (dialect in listOf("", "doris", "duckdb", "presto", "trino")) {
            assertFailsWith<ParseError>(dialect) { Dialects.forName(dialect).parseOne("FROM t |> RENAME id AS x c") }
        }
    }
}
