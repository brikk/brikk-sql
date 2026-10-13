package dev.brikk.house.sql.scope

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Relation scopes stay linear in the statement: four times the input may take at most ten times
 * as long (linear is about four, quadratic about sixteen). Timings are the best of several runs.
 */
class RelationScopeScalingTest {
    private val facts = RelationFacts(slots = mapOf("src" to RelationFact((1..50).map { ColumnFact("c$it") })))

    private fun bestMillis(block: () -> Unit): Double {
        repeat(2) { block() }
        return (1..5).minOf {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1e6
        }
    }

    private fun assertLinear(name: String, input: (Int) -> String, run: (String) -> Unit) {
        val small = input(1)
        val large = input(4)
        val smallMillis = bestMillis { run(small) }
        val largeMillis = bestMillis { run(large) }
        println("$name: ${small.length} chars %.1f ms, ${large.length} chars %.1f ms".format(smallMillis, largeMillis))
        assertTrue(largeMillis <= 10 * maxOf(smallMillis, 2.0), "$name is not linear: %.1f ms -> %.1f ms".format(smallMillis, largeMillis))
    }

    private fun wide(scale: Int) = "SELECT " + (1..500 * scale).joinToString { "s.c${it % 50 + 1} + ${it} AS x$it" } +
        " FROM src() s WHERE " + (1..500 * scale).joinToString(" AND ") { "s.c${it % 50 + 1} > $it" }

    private fun deep(scale: Int) = "FROM src() s" + (1..100 * scale).joinToString("") {
        " |> WHERE c${it % 50 + 1} > $it |> EXTEND c${it % 50 + 1} + 1 AS e$it"
    }

    @Test
    fun analysisIsLinear() {
        assertLinear("wide statement", ::wide) { RelationScope.analyze(it, "duckdb", facts) }
        assertLinear("deep pipe", ::deep) { RelationScope.analyze(it, "duckdb", facts) }
    }

    @Test
    fun completionIsLinear() {
        // Incomplete at the end, so recovery runs too.
        assertLinear("wide completion", { "${wide(it)} AND " }) { RelationScope.complete(it, "duckdb", facts, it.length) }
        assertLinear("deep completion", { "${deep(it)} |> WHERE " }) { RelationScope.complete(it, "duckdb", facts, it.length) }
    }
}
