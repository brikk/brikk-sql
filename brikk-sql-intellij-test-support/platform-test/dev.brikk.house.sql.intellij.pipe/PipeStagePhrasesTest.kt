package dev.brikk.house.sql.intellij.pipe

import dev.brikk.house.sql.intellij.catalog.embeddedDuckdb
import dev.brikk.house.sql.parser.pipeStageKeywords
import dev.brikk.house.sql.shape.PipeLowering
import dev.brikk.house.sql.shape.lowerForExecution
import junit.framework.TestCase

/** Each engine's stage phrases are ones core parses and lowers for it; DuckDB also runs them here. */
class PipeStagePhrasesTest : TestCase() {
    private val engines = listOf("trino", "duckdb", "doris", "clickhouse")

    /** A statement over `t(a, b)` and `u(a, c)` whose last stage starts with [phrase]. */
    private fun sample(dialect: String, phrase: String): String = "FROM t |> " + when (phrase) {
        "AGGREGATE" -> "AGGREGATE COUNT(*) AS n GROUP BY a"
        "AS" -> "AS x |> SELECT x.a"
        "CALL" -> "CALL summary()"
        "DISTINCT" -> "DISTINCT"
        "DROP" -> "DROP b"
        "EXTEND" -> "EXTEND a + 1 AS e"
        "LIMIT", "OFFSET" -> "$phrase 1"
        "ORDER BY" -> "ORDER BY a"
        "PIVOT" -> "PIVOT (SUM(b) FOR a IN (1, 2))"
        "RENAME" -> "RENAME a AS z"
        "SELECT" -> "SELECT a"
        "SET" -> "SET b = b + 1"
        "TABLESAMPLE" -> if (dialect == "clickhouse") "TABLESAMPLE 0.5" else "TABLESAMPLE SYSTEM (50 PERCENT)"
        "UNPIVOT" -> "UNPIVOT (v FOR k IN (a, b))"
        "WHERE" -> "WHERE a > 0"
        "WINDOW" -> "WINDOW SUM(b) OVER (ORDER BY a) AS s"
        "ARRAY JOIN" -> "ARRAY JOIN [1, 2] AS x"
        "CROSS JOIN", "NATURAL JOIN", "POSITIONAL JOIN" -> "$phrase u"
        "ASOF JOIN" -> "ASOF JOIN u ON t.a = u.a AND t.b >= u.c"
        else -> if (phrase.endsWith("JOIN")) "$phrase u ON t.a = u.a" else "$phrase (FROM u)"
    }

    private fun lowered(dialect: String, phrase: String): String {
        val sql = sample(dialect, phrase)
        val lowering = lowerForExecution(sql, dialect)
        assertTrue("$dialect: $sql -> $lowering", lowering is PipeLowering.Ok && lowering.blockers.isEmpty())
        return (lowering as PipeLowering.Ok).sql
    }

    fun testEveryPhraseIsParsedAndLoweredForItsDialect() {
        for (dialect in engines) {
            val phrases = pipeStagePhrases(dialect)
            assertTrue(dialect, phrases.isNotEmpty())
            assertEquals(dialect, pipeStageKeywords(dialect).filter { it in phrases }, phrases)
            phrases.forEach { lowered(dialect, it) }
        }
    }

    fun testDuckdbRunsEveryPhraseAsLowered() {
        embeddedDuckdb().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t (a INT, b INT); CREATE TABLE u (a INT, c INT)")
                for (phrase in pipeStagePhrases("duckdb")) statement.executeQuery(lowered("duckdb", phrase)).close()
            }
        }
    }

    fun testPhrasesTheEngineCannotRunAreLeftOut() {
        assertDoesntContain(pipeStagePhrases("trino"), "STRAIGHT_JOIN", "ASOF JOIN", "NATURAL JOIN", "CALL")
        assertDoesntContain(pipeStagePhrases("duckdb"), "STRAIGHT_JOIN")
        assertDoesntContain(pipeStagePhrases("doris"), "STRAIGHT_JOIN", "NATURAL JOIN")
        assertDoesntContain(pipeStagePhrases("clickhouse"), "STRAIGHT_JOIN")
    }

    fun testClickhouseNaturalJoinPhrasesKeepThePinnedLoweringEvidence() {
        // Batch-4 native probes execute this authored pipe and its actual lowering on
        // ClickHouse 26.8.34.13 and 26.10.2.103. NATURAL is no longer a refused stage.
        assertTrue("NATURAL JOIN" in pipeStagePhrases("clickhouse"))
        lowered("clickhouse", "NATURAL JOIN")
        val sql = "FROM numbers(2) |> AS a |> NATURAL LEFT JOIN (SELECT 0 number, 'R' r) b |> SELECT number,r |> ORDER BY number"
        val result = lowerForExecution(sql, "clickhouse")
        assertTrue(result.toString(), result is PipeLowering.Ok && result.blockers.isEmpty())
        assertFalse((result as PipeLowering.Ok).sql.contains("|>"))
    }

    fun testADialectWithoutEvidenceGetsEveryParsedPhrase() {
        assertEquals(pipeStageKeywords("bigquery"), pipeStagePhrases("bigquery"))
    }

    private fun assertDoesntContain(phrases: List<String>, vararg left: String) =
        left.forEach { assertFalse("$it in $phrases", it in phrases) }
}
