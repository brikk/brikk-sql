package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.DorisDialect
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.shape.SqlScriptParameters
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Editor entry points must stay linear: four times the input may take at most ten times as
 * long (linear is about four, quadratic about sixteen). Timings are the best of several runs.
 */
class EditorScalingTest {

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

    private fun script(scale: Int, newline: String) =
        (1..10_000 * scale).joinToString(newline) { "SELECT a$it, :p$it FROM t WHERE x = 'v';" }

    @Test
    fun tokenizerIsLinearForEveryLineBreak() {
        for ((label, newline) in listOf("LF" to "\n", "CRLF" to "\r\n", "CR" to "\r")) {
            assertLinear("tokenize $label", { script(it, newline) }) { Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenize(it) }
        }
    }

    @Test
    fun snapshotIsLinear() = assertLinear("snapshot", { script(it, "\n") }) { SqlScriptSnapshot.parse(it, "doris") }

    @Test
    fun editorParametersAreLinear() {
        assertLinear("parameters", { script(it, "\n") }) { SqlScriptParameters.parse(it, "doris") }
        assertLinear("deep parameters", { "SELECT " + "(".repeat(4_000 * it) + ":p ]".repeat(4_000 * it) }) {
            SqlScriptParameters.parse(it, "doris")
        }
    }
}
