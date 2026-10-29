package dev.brikk.house.sql.scope

import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Relation scopes stay linear in the statement: four times the input may take at most ten times
 * as long (linear is about four, quadratic about sixteen). Warm both sizes before sampling and
 * collect discarded results outside each timed run so earlier samples do not fill its heap.
 */
class RelationScopeScalingTest {
    private val facts = RelationFacts(slots = mapOf("src" to RelationFact((1..50).map { ColumnFact("c$it") })))

    private fun bestMillis(block: () -> Unit): Double {
        return (1..5).minOf {
            System.gc()
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1e6
        }
    }

    private fun assertLinear(name: String, input: (Int) -> String, run: (String) -> Unit) {
        val small = input(1)
        val large = input(4)
        repeat(2) { run(small); run(large) }
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

    /** Every WHERE after a LIMIT is an input boundary, checked against the CTE chain before it. */
    private fun boundaries(scale: Int) = "FROM src() s" + (1..75 * scale).joinToString("") { " |> LIMIT ${1000 - it} |> WHERE c1 > $it" }

    private fun repeated(scale: Int) = "SELECT * FROM (SELECT " + (1..4_000 * scale).joinToString { "c1" } + " FROM src()) x"

    @Test
    fun analysisIsLinear() {
        assertLinear("wide statement", ::wide) { RelationScope.analyze(it, "duckdb", facts) }
        assertLinear("deep pipe", ::deep) { RelationScope.analyze(it, "duckdb", facts) }
        assertLinear("pipe boundaries", ::boundaries) { RelationScope.analyze(it, "duckdb", facts) }
        assertLinear("repeated names", ::repeated) { RelationScope.analyze(it, "duckdb", facts) }
    }

    @Test
    fun parsingAndLoweringCheckCancellation() {
        val text = boundaries(4)
        var parsing = 0
        val region = checkNotNull(parseRegion(text, Dialects.DUCKDB) { parsing++ })
        assertTrue(parsing > region.tokens.size / 1024, "$parsing checks for ${region.tokens.size} tokens")
        var lowering = 0
        desugarPipes(region.statements.single(), Dialects.DUCKDB, copy = false, refused = { _, _ -> }) { lowering++ }
        assertTrue(lowering >= 600, "$lowering checks for 600 stages")
    }

    @Test
    fun completionIsLinear() {
        // Incomplete at the end, so recovery runs too.
        assertLinear("wide completion", { "${wide(it)} AND " }) { RelationScope.complete(it, "duckdb", facts, it.length) }
        assertLinear("deep completion", { "${deep(it)} |> WHERE " }) { RelationScope.complete(it, "duckdb", facts, it.length) }
    }
}
