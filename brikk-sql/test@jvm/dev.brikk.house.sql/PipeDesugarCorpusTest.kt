package dev.brikk.house.sql

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MANDATORY gate (100%): every pipe-syntax identity case from dialect-corpus/base.json
 * (sql contains "|>") must:
 *  1. parse into an AST that KEEPS the pipe stage structure (contains a PipeQuery);
 *  2. desugar + generate to the Python oracle's output, except for the explicit
 *     stage-semantic corrections below. Extracted oracle fixtures remain unmodified.
 */
class PipeDesugarCorpusTest {

    // brikk extensions: offsets consume the remaining limited slice, WHERE stays after DISTINCT,
    // and PIVOT/UNPIVOT read the joined rows rather than the first table (sqlglot attaches them to
    // the FROM table, ahead of the JOIN). Independent row-result tests cover these policies.
    private val corrections = mapOf(
        "FROM x |> JOIN y on x.id = y.id |> PIVOT(SUM(x1) FOR quarter IN ('foo1', 'foo2'))" to
            "WITH __tmp1 AS (SELECT * FROM x JOIN y ON x.id = y.id), __tmp2 AS (SELECT * FROM __tmp1 PIVOT(SUM(x1) FOR quarter IN ('foo1', 'foo2'))) SELECT * FROM __tmp2",
        "FROM x |> JOIN y on x.id = y.id |> UNPIVOT(col FOR item IN (foo1, foo2))" to
            "WITH __tmp1 AS (SELECT * FROM x JOIN y ON x.id = y.id), __tmp2 AS (SELECT * FROM __tmp1 UNPIVOT(col FOR item IN (foo1, foo2))) SELECT * FROM __tmp2",
        "FROM x |> SELECT x1, x2 |> LIMIT 2 OFFSET 2 |> LIMIT 4 OFFSET 2" to
            "WITH __tmp1 AS (SELECT x1, x2 FROM x) SELECT * FROM __tmp1 LIMIT 0 OFFSET 4",
        "FROM x |> DISTINCT |> WHERE x1 > 1" to
            "WITH __tmp1 AS (SELECT DISTINCT * FROM x) SELECT * FROM __tmp1 AS x WHERE x1 > 1",
    )

    data class PipeCase(val sql: String, val expected: String, val pretty: Boolean)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun loadPipeCases(): List<PipeCase> {
            val root = json.parseToJsonElement(testResource("dialect-corpus/base.json")).jsonObject
            return root.getValue("identity").jsonArray
                .map { it.jsonObject }
                .filter { "|>" in it.getValue("sql").jsonPrimitive.content }
                .map { case ->
                    val sql = case.getValue("sql").jsonPrimitive.content
                    val expected = case["expected"]?.jsonPrimitive?.contentOrNull ?: sql
                    val pretty = case["pretty"]?.jsonPrimitive?.booleanOrNull ?: false
                    PipeCase(sql, expected, pretty)
                }
        }
    }

    @Test
    fun pipeCorpusDesugarsToOracleOrRegisteredCorrections() {
        val cases = loadPipeCases()
        check(cases.isNotEmpty()) { "no pipe cases found in dialect-corpus/base.json" }

        val failures = mutableListOf<String>()
        val usedCorrections = mutableSetOf<String>()

        for (case in cases) {
            val expected = corrections[case.sql]?.also {
                usedCorrections.add(case.sql)
                assertNotEquals(case.expected, it, "Upstream adopted this correction; remove its override")
            } ?: case.expected
            val result = runCatching {
                val ast = parseOne(case.sql)
                check(ast.findAll<PipeQuery>().any()) { "no PipeQuery in parsed AST (stage structure lost)" }
                Generator(pretty = case.pretty).generate(desugarPipes(ast))
            }

            val actual = result.getOrNull()
            if (actual != expected) {
                val reason = result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
                failures.add(
                    "SQL: ${case.sql}\n" +
                        (reason?.let { "  error:    $it\n" } ?: "") +
                        "  expected: $expected\n" +
                        "  actual:   $actual"
                )
            }
        }
        assertEquals(corrections.keys, usedCorrections, "Every correction must cover a current corpus case")

        println("PipeDesugarCorpus: ${cases.size - failures.size}/${cases.size} pass (${cases.size} pipe cases found)")

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size}/${cases.size} pipe desugar failures (showing up to 25):\n\n" +
                    failures.take(25).joinToString("\n\n")
            )
        }
    }
}
