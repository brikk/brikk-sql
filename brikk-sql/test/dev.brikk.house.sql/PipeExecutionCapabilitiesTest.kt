package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipeExecutionCapabilitiesTest {
    private val targets = listOf("presto", "trino")
    private val complete = ShapeCatalog(tables = mapOf("t" to
        Shape.of("label" to "VARCHAR", "id" to "INT", "payload" to "BIGINT")))

    @Test
    fun unknownLayoutDropAndSetRefuseAllExecutableRoutesWithoutMutatingSource() {
        for (stage in listOf("DROP id", "SET id = 1", "SET id = id + 1")) {
            for (target in targets) {
                val fragment = SqlFragment("FROM t |> $stage", target)
                val original = fragment.ast.copy()
                for (pretty in listOf(false, true)) {
                    for (tracked in listOf(false, true)) {
                        val error = assertFailsWith<UnsupportedError>("$target/$stage") {
                            fragment.toExecutable(target, pretty = pretty, trackSourceMap = tracked)
                        }
                        assertTrue(error.message.orEmpty().contains(if (stage.startsWith("DROP")) "EXCEPT" else "REPLACE"))
                        assertFailsWith<UnsupportedError> { fragment.transpileTo(target, pretty, tracked, desugarPipes = true) }
                    }
                }
                assertFailsWith<UnsupportedError> { fragment.toStandardSql(target) }
                assertFailsWith<UnsupportedError> { fragment.toSourcePreservingExecutable(target) }
                assertEquals(original, fragment.ast)
            }
        }
    }

    @Test
    fun directQualifiedStarsNestedPipesAndUnresolvedJoinsCannotBypassModifierGuard() {
        for (target in targets) {
            for (source in listOf(
                "SELECT * EXCEPT (id) FROM t", "SELECT t.* EXCEPT (id) FROM t",
                "SELECT * REPLACE (1 AS id) FROM t", "SELECT t.* REPLACE (1 AS id) FROM t",
                "SELECT * EXCEPT (payload) REPLACE (1 AS id) FROM t",
            )) {
                assertFailsWith<UnsupportedError>(source) { Dialects.forName(target).generate(Dialects.DUCKDB.parseOne(source)) }
            }
            for (stage in listOf("DROP id", "SET id = 1")) {
                val nested = SqlFragment("WITH p AS (FROM t |> $stage) SELECT * FROM p", target)
                assertFailsWith<UnsupportedError> { nested.toExecutable(target) }
                assertFailsWith<UnsupportedError> { nested.toSourcePreservingExecutable(target) }
                assertFailsWith<UnsupportedError> {
                    SqlFragment("FROM t CROSS JOIN mystery |> $stage", target)
                        .toStandardSql(target, complete, expandStars = true)
                }
            }
        }
    }

    @Test
    fun completeLayoutsExpandDropAndSetInPlaceWhileTraitMinimumIsNotAnExecutionProof() {
        for (target in targets) {
            for ((stage, names) in listOf(
                "DROP id" to listOf("label", "payload"),
                "DROP id, payload" to listOf("label"),
                "SET id = 1" to listOf("label", "id", "payload"),
                "SET payload = id, id = payload" to listOf("label", "id", "payload"),
            )) {
                val fragment = SqlFragment("FROM t |> $stage", target)
                val sql = fragment.toStandardSql(target, complete, expandStars = true)
                assertFalse("* EXCEPT" in sql || "* REPLACE" in sql, sql)
                assertEquals(names, SqlFragment(sql, target).outputShape(complete).names(), sql)
                assertEquals(emptyList(), SqlFragment(sql, target).toExecutable(target).unsupportedMessages)
            }
            val minimum = ShapeCatalog(tables = emptyMap(), slots = mapOf("src" to Shape.of("id" to "INT")))
            val fragment = SqlFragment("FROM src() |> SET id = 1", target)
            assertEquals(listOf("id"), fragment.outputShape(minimum).names())
            assertFailsWith<UnsupportedError> { fragment.toSourcePreservingExecutable(target) }
        }
    }

    @Test
    fun nativeDorisAndDuckdbModifiersOrdinaryStarsSetExceptAndAuthorFormattingStayAvailable() {
        for (target in listOf("doris", "duckdb")) {
            for ((stage, modifier) in listOf("DROP id" to "EXCEPT", "SET id = 1" to "REPLACE")) {
                val result = SqlFragment("FROM t |> $stage", target).toExecutable(target)
                val nativeModifier = if (target == "duckdb" && modifier == "EXCEPT") "EXCLUDE" else modifier
                assertTrue("* $nativeModifier" in result.sql, result.sql)
                assertEquals(emptyList(), result.unsupportedMessages)
            }
        }
        for (target in targets) {
            assertEquals(emptyList(), SqlFragment("SELECT * FROM t", target).toExecutable(target).unsupportedMessages)
            assertEquals(emptyList(), SqlFragment("SELECT 1 AS n EXCEPT SELECT 2 AS n", target)
                .toExecutable(target).unsupportedMessages)
            for (stage in listOf("DROP id", "SET id = 1")) {
                val source = "FROM t |> $stage"
                assertEquals(source, SqlFragment(source, target).transpileTo(target, desugarPipes = false).sql)
            }
        }
    }

    @Test
    fun unsupportedPivotAndUnpivotRefuseExecutablePathsButKeepDiagnosticPreview() {
        for (target in listOf("presto", "trino", "mysql", "doris", "postgres", "clickhouse")) {
            for (stage in listOf("PIVOT (SUM(id) FOR payload IN (1, 2))", "UNPIVOT (v FOR k IN (id, payload))")) {
                val fragment = SqlFragment("FROM t |> $stage", target)
                val original = fragment.ast.copy()
                val preview = fragment.transpileTo(target, desugarPipes = true)
                assertTrue(preview.unsupportedMessages.any { "PIVOT" in it }, "$target: $preview")
                assertFailsWith<UnsupportedError>(target) { fragment.toExecutable(target) }
                assertFailsWith<UnsupportedError>(target) { fragment.toStandardSql(target) }
                assertFailsWith<UnsupportedError>(target) { fragment.toSourcePreservingExecutable(target) }
                val nested = SqlFragment("WITH p AS (${fragment.sql}) SELECT * FROM p", target)
                assertFailsWith<UnsupportedError>(target) { nested.toExecutable(target) }
                assertFailsWith<UnsupportedError>(target) { nested.toSourcePreservingExecutable(target) }
                assertEquals(original, fragment.ast)
            }
        }
    }

    @Test
    fun nativeDuckdbPivotAndUnpivotDoNotBecomeRefusals() {
        for (stage in listOf("PIVOT (SUM(id) FOR payload IN (1, 2))", "UNPIVOT (v FOR k IN (id, payload))")) {
            val result = SqlFragment("FROM t |> $stage", "duckdb").toExecutable("duckdb")
            assertTrue("PIVOT" in result.sql, result.sql)
            assertEquals(emptyList(), result.unsupportedMessages)
        }
    }
}
